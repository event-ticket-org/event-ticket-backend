package com.eventticket.payment;

import org.springframework.data.mongodb.core.query.Criteria;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
import com.eventticket.api.model.NextAction;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.OrderStatus;
import com.eventticket.api.model.PaymentSession;
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.api.model.Ticket;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Knowledge base requirements/005. */
class PaymentConfirmationTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("starting a payment returns a next action the client renders blindly")
    void startingAPaymentReturnsANextAction() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = checkout(buyer, eventId, seatIdsOf(eventId, 2)).getBody();

        PaymentSession session = startPayment(buyer, order.getId());

        assertThat(session.getStatus()).isEqualTo(PaymentSession.StatusEnum.AWAITING_PAYMENT);
        assertThat(session.getProvider()).isEqualTo("FAKE");
        assertThat(session.getNextAction().getType()).isEqualTo(NextAction.TypeEnum.DISPLAY_QR);
        assertThat(session.getNextAction().getQrPayload()).isNotBlank();
        // VietQR needs a memo on the transfer; there is nowhere else in a redirect-shaped
        // model to put one, which is why NextAction carries it.
        assertThat(session.getNextAction().getReference()).isNotBlank();
    }

    @Test
    @DisplayName("the buyer returning to the site never confirms an order; the webhook does")
    void onlyTheWebhookConfirms() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 2);
        Order order = checkout(buyer, eventId, seats).getBody();
        PaymentSession session = startPayment(buyer, order.getId());

        // The buyer reloading their order changes nothing.
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(ticketsOf(buyer, order.getId())).isEmpty();

        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(session), "PAID");

        Order paid = orderOf(buyer, order.getId());
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getHoldExpiresAt()).isNull();
        assertThat(ticketsOf(buyer, order.getId())).hasSize(2);
        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.SOLD);
    }

    @Test
    @DisplayName("a payload with a bad signature is refused and nothing is paid")
    void unsignedWebhooksAreRefused() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = checkout(buyer, eventId, seatIdsOf(eventId, 1)).getBody();
        PaymentSession session = startPayment(buyer, order.getId());

        String body = """
                {"eventId":"%s","providerRef":"%s","status":"PAID"}"""
                .formatted(UUID.randomUUID(), providerRefOf(session));

        assertThat(deliverWebhookRaw(body, "00".repeat(32)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(ticketsOf(buyer, order.getId())).isEmpty();

        // The same body, signed, is accepted - so the refusal was the signature and nothing else.
        assertThat(deliverWebhookRaw(body, fakeProvider.signatureFor(body.getBytes(StandardCharsets.UTF_8)))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("the same confirmation twice produces one paid order and one set of tickets")
    void repeatedDeliveryIsIdempotent() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = checkout(buyer, eventId, seatIdsOf(eventId, 3)).getBody();
        PaymentSession session = startPayment(buyer, order.getId());
        String deliveryId = UUID.randomUUID().toString();

        deliverWebhook(deliveryId, providerRefOf(session), "PAID");
        deliverWebhook(deliveryId, providerRefOf(session), "PAID");
        deliverWebhook(deliveryId, providerRefOf(session), "PAID");

        assertThat(ticketsOf(buyer, order.getId())).hasSize(3);
        assertThat(countIn("ticket", Criteria.where("orderId").is(order.getId()))).isEqualTo(3L);
    }

    @Test
    @DisplayName("two deliveries racing each other still produce one set of tickets")
    void concurrentDeliveriesAreIdempotent() throws Exception {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = checkout(buyer, eventId, seatIdsOf(eventId, 3)).getBody();
        PaymentSession session = startPayment(buyer, order.getId());
        String deliveryId = UUID.randomUUID().toString();
        String ref = providerRefOf(session);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<? extends Future<?>> deliveries = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return deliverWebhook(deliveryId, ref, "PAID").getStatusCode();
                    })).toList();
            start.countDown();
            for (Future<?> delivery : deliveries) {
                delivery.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(countIn("ticket", Criteria.where("orderId").is(order.getId()))).isEqualTo(3L);
        assertThat(countIn("paymentEvent")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a confirmation for a session we never had is acknowledged and ignored")
    void unknownSessionsAreAcknowledged() {
        assertThat(deliverWebhook(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "PAID")
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("money arriving after the holds lapsed fails the order and flags a refund")
    void confirmationAfterHoldsLapsed() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 2);
        Order order = checkout(buyer, eventId, seats).getBody();
        PaymentSession session = startPayment(buyer, order.getId());

        // The buyer took too long at the bank. The seats went back on sale, and somebody else
        // is already looking at them.
        expireHoldsOf(order.getId());

        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(session), "PAID");

        Order failed = orderOf(buyer, order.getId());
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(ticketsOf(buyer, order.getId())).isEmpty();

        // The money is ours and should not be. requirements/005 criterion 9.
        assertThat(readField("ticketOrder", order.getId(), "refundRequired", Boolean.class)).isTrue();
        assertThat(countIn("auditEntry",
                Criteria.where("action").is("ORDER_REFUND_REQUIRED"))).isEqualTo(1L);

        // The seats are free, not half-sold.
        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("a payment cannot be started for an order whose holds have gone")
    void expiredOrdersCannotBePaid() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = checkout(buyer, eventId, seatIdsOf(eventId, 1)).getBody();

        expireHoldsOf(order.getId());

        var refused = exchange(HttpMethod.POST, "/orders/" + order.getId() + "/payment-sessions",
                buyer, new com.eventticket.api.model.StartPaymentRequest("FAKE"),
                com.eventticket.api.model.Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode())
                .isEqualTo(com.eventticket.api.model.ErrorCode.HOLD_EXPIRED);
    }

    @Test
    @DisplayName("a failed payment closes the session and leaves the holds alone")
    void failedPaymentsKeepTheSeats() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 1);
        Order order = checkout(buyer, eventId, seats).getBody();
        PaymentSession session = startPayment(buyer, order.getId());

        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(session), "FAILED");

        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.HELD);

        // criterion 11: many attempts, at most one success. A second attempt is allowed while
        // the holds are alive.
        PaymentSession retry = startPayment(buyer, order.getId());
        assertThat(retry.getId()).isNotEqualTo(session.getId());
        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(retry), "PAID");
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // --- fixtures -------------------------------------------------------------------------

    private Order orderOf(TokenPair session, UUID orderId) {
        return exchange(HttpMethod.GET, "/orders/" + orderId, session, null, Order.class).getBody();
    }

    private List<Ticket> ticketsOf(TokenPair session, UUID orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.getAccessToken());
        return http.exchange("/orders/" + orderId + "/tickets", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<Ticket>>() {}).getBody();
    }

    private SeatAvailability availabilityOf(UUID eventId, UUID seatId) {
        return publicSeatMap(eventId).getSeats().stream()
                .filter(seat -> seat.getId().equals(seatId))
                .map(com.eventticket.api.model.EventSeat::getAvailability)
                .findFirst().orElseThrow();
    }

    private UUID publishedEvent() {
        TokenPair alice = signUp("alice@example.com");
        var organization = createOrganization(alice, "Acme Events");
        approve(organization);
        TokenPair manager = switchTo(alice, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);
        publish(manager, event.getId(), Event.class);
        return event.getId();
    }
}
