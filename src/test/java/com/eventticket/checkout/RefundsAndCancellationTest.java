package com.eventticket.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.EventCancellation;
import com.eventticket.api.model.EventStatus;
import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.Membership;
import com.eventticket.api.model.Money;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.OrderPage;
import com.eventticket.api.model.OrderStatus;
import com.eventticket.api.model.PaymentSession;
import com.eventticket.api.model.Refund;
import com.eventticket.api.model.RefundRequest;
import com.eventticket.api.model.RefundStatus;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.ScanOutcome;
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Knowledge base requirements/008, and KB invariants 21 and 22. */
class RefundsAndCancellationTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("a refund is pending until the provider says the money moved")
    void aRefundSettlesOnTheProvidersWord() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 2));

        Refund started = refund(organizer.manager(), order.getId(), "The support act cancelled.");

        // criterion 2: never an instant boolean. The Order is still paid, because it is - the
        // provider has been asked and has not answered.
        assertThat(started.getStatus()).isEqualTo(RefundStatus.REFUND_PENDING);
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);

        deliverRefundWebhook(refundRefOf(started), "REFUNDED");

        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refundsOf(organizer.manager(), order.getId()))
                .singleElement()
                .satisfies(settled -> {
                    assertThat(settled.getStatus()).isEqualTo(RefundStatus.REFUNDED);
                    assertThat(settled.getSettledAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("refunding voids the tickets, and the door says so in its own words")
    void refundedTicketsAreRefusedAtTheDoor() {
        Organizer organizer = organizer(doorsOpenNow());
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));
        String code = ticketCodesOf(buyer, order.getId()).get(0);

        refund(organizer.manager(), order.getId(), "Wrong date advertised.");

        // criterion 4, and immediately: the refund has not settled yet, and a Ticket that still
        // admitted here would be somebody getting in and getting their money back.
        assertThat(scan(organizer.manager(), organizer.eventId(), code, "Door 1").getOutcome())
                .isEqualTo(ScanOutcome.TICKET_VOID);
    }

    @Test
    @DisplayName("a seat goes back on sale when the money has actually gone back, not before")
    void seatsReturnToSaleOnlyOnceTheRefundSettles() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(organizer.eventId(), 1);
        Order order = buyAndPay(buyer, organizer.eventId(), seats);
        assertThat(availabilityOf(organizer.eventId(), seats.get(0))).isEqualTo(SeatAvailability.SOLD);

        Refund started = refund(organizer.manager(), order.getId(), "Double booked.");

        // Still sold. Selling it to somebody else now would leave this buyer with neither the
        // seat nor the money, if the refund then failed.
        assertThat(availabilityOf(organizer.eventId(), seats.get(0))).isEqualTo(SeatAvailability.SOLD);

        deliverRefundWebhook(refundRefOf(started), "REFUNDED");

        // criterion 5.
        assertThat(availabilityOf(organizer.eventId(), seats.get(0)))
                .isEqualTo(SeatAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("a refund the provider refuses is kept, with the reason, and changes nothing")
    void aFailedRefundSaysWhy() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(organizer.eventId(), 1);
        Order order = buyAndPay(buyer, organizer.eventId(), seats);

        Refund started = refund(organizer.manager(), order.getId(), "Wrong date.");
        deliverRefundWebhook(refundRefOf(started), "REFUND_FAILED", "Card account closed.");

        assertThat(refundsOf(organizer.manager(), order.getId()))
                .singleElement()
                .satisfies(failed -> {
                    assertThat(failed.getStatus()).isEqualTo(RefundStatus.REFUND_FAILED);
                    assertThat(failed.getFailureReason()).isEqualTo("Card account closed.");
                });

        // The Order is left holding the money, because it is, and the seat stays off sale.
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(availabilityOf(organizer.eventId(), seats.get(0))).isEqualTo(SeatAvailability.SOLD);
    }

    @Test
    @DisplayName("a second refund while one is in flight is refused in words, not by a constraint")
    void oneLiveRefundPerOrder() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        Refund first = refund(organizer.manager(), order.getId(), "Wrong date.");

        var refused = exchange(HttpMethod.POST, "/orders/" + order.getId() + "/refunds",
                organizer.manager(), reason("Clicked twice."), Error.class);

        // The partial unique index makes this true concurrently; this is what makes it legible.
        // Without it the answer was a 500 reading "the request could not be completed", which
        // is the worst thing to say to somebody who has just double-clicked a refund button.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.ORDER_NOT_REFUNDABLE);
        assertThat(refused.getBody().getMessage()).contains("already with the payment provider");
        assertThat(jdbc.queryForObject("select count(*) from refund where order_id = ?",
                Long.class, order.getId())).isEqualTo(1L);

        // And once it has settled, the refusal changes its words rather than staying stale.
        deliverRefundWebhook(refundRefOf(first), "REFUNDED");
        assertThat(exchange(HttpMethod.POST, "/orders/" + order.getId() + "/refunds",
                organizer.manager(), reason("And again."), Error.class).getBody().getMessage())
                .contains("already been refunded");
    }

    @Test
    @DisplayName("a refund the provider refused can be attempted again")
    void aFailedRefundCanBeRetried() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        Refund first = refund(organizer.manager(), order.getId(), "Wrong date.");
        deliverRefundWebhook(refundRefOf(first), "REFUND_FAILED", "Account closed.");

        // The whole reason the unique index excludes failed attempts: this is the case where
        // trying again is the right answer, and the history keeps both.
        Refund second = refund(organizer.manager(), order.getId(), "Trying the other account.");
        deliverRefundWebhook(refundRefOf(second), "REFUNDED");

        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refundsOf(organizer.manager(), order.getId())).hasSize(2);
    }

    @Test
    @DisplayName("a ticket that has been used at the door can never be refunded")
    void aRedeemedTicketIsNeverRefunded() {
        Organizer organizer = organizer(doorsOpenNow());
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 2));
        String code = ticketCodesOf(buyer, order.getId()).get(0);
        scan(organizer.manager(), organizer.eventId(), code, "Door 1");

        var refused = exchange(HttpMethod.POST, "/orders/" + order.getId() + "/refunds",
                organizer.manager(), reason("Changed my mind."), Error.class);

        // criterion 3, KB invariant 21. Somebody has been let in, and no refund takes it back.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.ORDER_NOT_REFUNDABLE);
        assertThat(refused.getBody().getMessage()).contains("already been used at the door");
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("a buyer cannot refund themselves, and gate staff cannot refund at all")
    void refundingIsForOwnersAndManagers() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        // criterion 1. The buyer holds a valid session and owns the Order, and is still refused.
        assertThat(exchange(HttpMethod.POST, "/orders/" + order.getId() + "/refunds",
                buyer, reason("I changed my mind."), Error.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        TokenPair gate = gateStaffOf(organizer);
        assertThat(exchange(HttpMethod.POST, "/orders/" + order.getId() + "/refunds",
                gate, reason("On the owner's say-so."), Error.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("money taken after the holds lapsed can be given back, which is the point")
    void anOrderFlaggedForRefundCanBeRefunded() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = checkout(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1)).getBody();
        PaymentSession session = startPayment(buyer, order.getId());

        // The seats lapse, and only then does the money arrive: requirements/005 criterion 9.
        jdbc.update("update event_seat set held_until = now() - interval '1 minute' "
                + "where held_by_order_id = ?", order.getId());
        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(session), "PAID");

        Order flagged = orderOf(buyer, order.getId());
        assertThat(flagged.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(flagged.getRefundRequired()).isTrue();

        // This Order was never PAID, and refusing it would refuse the one case 008 exists for.
        Refund started = refund(organizer.manager(), order.getId(), "Payment arrived too late.");
        deliverRefundWebhook(refundRefOf(started), "REFUNDED");

        Order refunded = orderOf(buyer, order.getId());
        assertThat(refunded.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.getRefundRequired()).isFalse();
    }

    @Test
    @DisplayName("an organizer can find the orders holding money that should be given back")
    void theOrdersNeedingRefundsAreFindable() {
        Organizer organizer = organizer();
        TokenPair unlucky = signUp("unlucky@example.com");
        TokenPair fine = signUp("fine@example.com");

        buyAndPay(fine, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        Order late = checkout(unlucky, organizer.eventId(), seatIdsOf(organizer.eventId(), 1)).getBody();
        PaymentSession session = startPayment(unlucky, late.getId());
        jdbc.update("update event_seat set held_until = now() - interval '1 minute' "
                + "where held_by_order_id = ?", late.getId());
        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(session), "PAID");

        // criterion 10: both Orders, then only the one holding money it should not.
        assertThat(eventOrders(organizer, null).getItems()).hasSize(2);

        OrderPage owed = eventOrders(organizer, "?refundRequired=true");
        assertThat(owed.getItems()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(late.getId());
            assertThat(row.getRefundRequired()).isTrue();
            // The organizer is refunding a person, and will have to answer to them.
            assertThat(row.getBuyerEmail()).isEqualTo("unlucky@example.com");
        });

        // And the count is on the Event, which is where an organizer would look for it.
        Event event = exchange(HttpMethod.GET, "/events/" + organizer.eventId(),
                organizer.manager(), null, Event.class).getBody();
        assertThat(event.getRefundRequiredCount()).isEqualTo(1);
        assertThat(event.getSoldCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling an event voids every ticket and refunds every paid order")
    void cancellingRefundsEverybody() {
        Organizer organizer = organizer(doorsOpenNow());
        TokenPair first = signUp("first@example.com");
        TokenPair second = signUp("second@example.com");
        Order one = buyAndPay(first, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));
        Order two = buyAndPay(second, organizer.eventId(), seatIdsOf(organizer.eventId(), 2));
        String code = ticketCodesOf(first, one.getId()).get(0);

        EventCancellation started = cancel(organizer, "The venue flooded.");

        // criterion 7: per Order, because this partially fails and somebody has to see which.
        assertThat(started.getOrders()).hasSize(2);
        assertThat(started.getOrders()).extracting(state -> state.getOrderId())
                .containsExactlyInAnyOrder(one.getId(), two.getId());
        assertThat(started.getPending()).isEqualTo(2);

        // criterion 6, the ticket half: void before any money has moved.
        assertThat(scan(organizer.manager(), organizer.eventId(), code, "Door 1").getOutcome())
                .isEqualTo(ScanOutcome.TICKET_VOID);
        assertThat(exchange(HttpMethod.GET, "/events/" + organizer.eventId(), organizer.manager(),
                null, Event.class).getBody().getStatus()).isEqualTo(EventStatus.CANCELLED);

        settleEveryRefund();

        EventCancellation done = exchange(HttpMethod.GET,
                "/events/" + organizer.eventId() + "/cancel", organizer.manager(), null,
                EventCancellation.class).getBody();
        assertThat(done.getRefunded()).isEqualTo(2);
        assertThat(done.getPending()).isZero();
        assertThat(done.getFailed()).isZero();
        assertThat(done.getReason()).isEqualTo("The venue flooded.");

        assertThat(orderOf(first, one.getId()).getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(orderOf(second, two.getId()).getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("one order failing to refund does not undo the others")
    void aPartialFailureIsReportedRatherThanRolledBack() {
        Organizer organizer = organizer(doorsOpenNow());
        TokenPair first = signUp("first@example.com");
        TokenPair second = signUp("second@example.com");
        Order refundable = buyAndPay(first, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));
        Order used = buyAndPay(second, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        // Somebody on the second Order has already been let in, so its refund is refused -
        // which is criterion 3 meeting criterion 7 in the middle of a bulk operation.
        scan(organizer.manager(), organizer.eventId(),
                ticketCodesOf(second, used.getId()).get(0), "Door 1");

        EventCancellation started = cancel(organizer, "Performer ill.");

        assertThat(started.getOrders()).hasSize(2);
        // The refundable one was still attempted: one failure did not roll the other back.
        assertThat(jdbc.queryForObject("select count(*) from refund where order_id = ?",
                Long.class, refundable.getId())).isEqualTo(1L);

        // criterion 7, and the part that is easy to get wrong: the refused Order is *reported*
        // as failed, with the reason, rather than left looking like one still in progress.
        // Without this it read "still going" for ever on the screen somebody watches to find
        // out what they have to finish by hand.
        assertThat(started.getOrders())
                .filteredOn(state -> state.getOrderId().equals(used.getId()))
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.getStatus()).isEqualTo(RefundStatus.REFUND_FAILED);
                    assertThat(state.getFailureReason()).contains("already been used at the door");
                });
        assertThat(started.getFailed()).isEqualTo(1);
        // And the other one is genuinely still going: asked for, and with the provider.
        assertThat(started.getPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("a draft event has nothing to cancel and says so")
    void cancellingADraftIsRefused() {
        Organizer organizer = organizer();
        Venue venue = createVenue(organizer.manager(), "Second Room", "Ho Chi Minh City");
        putSeatMap(organizer.manager(), venue.getId(), SeatMaps.block("Standard", 1, 2));
        Event draft = createEvent(organizer.manager(), venue.getId(), "Still Cooking", NEXT_MONTH);

        var refused = exchange(HttpMethod.POST, "/events/" + draft.getId() + "/cancel",
                organizer.manager(), reason("Changed our minds."), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.EVENT_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("refunds and cancellations are written to the audit log")
    void bothAreAudited() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        Refund started = refund(organizer.manager(), order.getId(), "Wrong date.");
        deliverRefundWebhook(refundRefOf(started), "REFUNDED");
        cancel(organizer, "And then the venue flooded.");

        // criterion 9, and KB invariant 23.
        assertThat(jdbc.queryForList("select action from audit_entry", String.class))
                .contains("ORDER_REFUND_STARTED", "ORDER_REFUNDED", "EVENT_CANCELLED");
    }

    /**
     * requirements/003 criterion 23. Two failure modes, and one test that would catch either.
     *
     * <p>The first is arithmetic. The query behind this joins Orders to their seats, so an
     * Order appears once per seat - and summing its total over that join multiplies it by the
     * number of seats on it. Every order here has more than one seat, so a total built that way
     * cannot pass: 2 seats and 3 seats at 250,000 would report 3,250,000 rather than 1,250,000.
     *
     * <p>The second is the point of the criterion. A refunded Order has given the money back,
     * and a figure that still counted it would tell an organizer they hold funds they do not.
     */
    @Test
    @DisplayName("an event reports the money it is holding, not the money it ever took")
    void salesTotalIsWhatIsHeld() {
        Organizer organizer = organizer();
        UUID eventId = organizer.eventId();

        assertThat(eventOf(organizer.manager(), eventId).getSalesTotal())
                .as("nothing sold yet")
                .satisfies(total -> {
                    assertThat(total.getAmount()).isZero();
                    assertThat(total.getCurrency()).isEqualTo(Money.CurrencyEnum.VND);
                });

        TokenPair first = signUp("first@example.com");
        Order twoSeats = buyAndPay(first, eventId, seatIdsOf(eventId, 2));
        TokenPair second = signUp("second@example.com");
        buyAndPay(second, eventId, seatIdsOf(eventId, 3));

        assertThat(eventOf(organizer.manager(), eventId))
                .as("five seats at 250,000, summed once each")
                .satisfies(event -> {
                    assertThat(event.getSalesTotal().getAmount()).isEqualTo(1_250_000L);
                    assertThat(event.getSoldCount()).isEqualTo(5);
                });

        Refund started = refund(organizer.manager(), twoSeats.getId(), "They could not come.");
        // Still held while the provider has only been asked: the money has not moved yet, and
        // the Order is still PAID. Dropping it here would be the mirror of the bug above.
        assertThat(eventOf(organizer.manager(), eventId).getSalesTotal().getAmount())
                .as("a refund that has not settled has not given anything back")
                .isEqualTo(1_250_000L);

        deliverRefundWebhook(refundRefOf(started), "REFUNDED");

        assertThat(eventOf(organizer.manager(), eventId))
                .as("the settled refund is no longer money this event holds")
                .satisfies(event -> {
                    assertThat(event.getSalesTotal().getAmount()).isEqualTo(750_000L);
                    assertThat(event.getSoldCount()).isEqualTo(3);
                });
    }

    /**
     * requirements/007 criterion 13: Gate Staff see no sales figures or revenue anywhere.
     *
     * <p>Kept by the endpoint refusing them rather than by the mapper blanking a field, which
     * is the stronger of the two - a blanked field has to be remembered at every future mapping
     * site, and a refusal cannot be forgotten.
     */
    @Test
    @DisplayName("gate staff are not shown an event at all, so they are not shown its takings")
    void gateStaffSeeNoTakings() {
        Organizer organizer = organizer();
        buyAndPay(signUp("buyer@example.com"), organizer.eventId(), seatIdsOf(organizer.eventId(), 2));

        var refused = exchange(HttpMethod.GET, "/events/" + organizer.eventId(),
                gateStaffOf(organizer), null, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- the scaffolding -------------------------------------------------------------------

    private record Organizer(TokenPair manager, UUID eventId,
                             com.eventticket.api.model.Organization organization) {}

    private Organizer organizer() {
        return organizer(NEXT_MONTH);
    }

    /** Doors open now, for the tests that need a scan to reach a Ticket rather than a window. */
    private OffsetDateTime doorsOpenNow() {
        return OffsetDateTime.now().plus(5, ChronoUnit.MINUTES);
    }

    private Organizer organizer(OffsetDateTime startsAt) {
        TokenPair alice = signUp("alice@example.com");
        var organization = createOrganization(alice, "Acme Events");
        approve(organization);
        TokenPair manager = switchTo(alice, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", startsAt,
                startsAt.minus(1, ChronoUnit.HOURS), startsAt.plus(3, ChronoUnit.HOURS));
        priceTier(manager, event.getId(), "Standard", 250_000);
        publish(manager, event.getId(), Event.class);
        return new Organizer(manager, event.getId(), organization);
    }

    private TokenPair gateStaffOf(Organizer organizer) {
        exchange(HttpMethod.POST, "/organization/members", organizer.manager(),
                new InviteMemberRequest("gate@example.com", Role.GATE_STAFF), Membership.class);
        return switchTo(signUp("gate@example.com"), organizer.organization());
    }

    private RefundRequest reason(String reason) {
        return new RefundRequest(reason);
    }

    private Event eventOf(TokenPair session, UUID eventId) {
        return exchange(HttpMethod.GET, "/events/" + eventId, session, null, Event.class).getBody();
    }

    private Refund refund(TokenPair session, UUID orderId, String reason) {
        var response = exchange(HttpMethod.POST, "/orders/" + orderId + "/refunds",
                session, reason(reason), Refund.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private List<Refund> refundsOf(TokenPair session, UUID orderId) {
        return List.of(exchange(HttpMethod.GET, "/orders/" + orderId + "/refunds",
                session, null, Refund[].class).getBody());
    }

    private EventCancellation cancel(Organizer organizer, String reason) {
        var response = exchange(HttpMethod.POST, "/events/" + organizer.eventId() + "/cancel",
                organizer.manager(), reason(reason), EventCancellation.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private OrderPage eventOrders(Organizer organizer, String query) {
        return exchange(HttpMethod.GET,
                "/events/" + organizer.eventId() + "/orders" + (query == null ? "" : query),
                organizer.manager(), null, OrderPage.class).getBody();
    }

    private Order orderOf(TokenPair session, UUID orderId) {
        return exchange(HttpMethod.GET, "/orders/" + orderId, session, null, Order.class).getBody();
    }

    private SeatAvailability availabilityOf(UUID eventId, UUID seatId) {
        return publicSeatMap(eventId).getSeats().stream()
                .filter(seat -> seat.getId().equals(seatId))
                .findFirst().orElseThrow().getAvailability();
    }

    /** The provider's handle for a reversal, which its callback names. */
    private String refundRefOf(Refund refund) {
        return jdbc.queryForObject("select provider_ref from refund where id = ?",
                String.class, refund.getId());
    }

    /**
     * requirements/008 criterion 11, and the reason it exists.
     *
     * <p>This is what Stripe actually did, reproduced through the fake: a refund reported
     * settled, and then - once the issuer rejected the card - reported failed. Before this,
     * the second delivery was discarded as a re-delivery of a refund that had already
     * settled, so the Order stayed REFUNDED, the seat stayed on sale, and the buyer kept an
     * email saying money had gone back that had not.
     */
    @Test
    @DisplayName("a provider that reverses a settled refund puts the order back in front of somebody")
    void aSettledRefundCanBeReversed() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("reversed-buyer@example.com");
        List<UUID> seats = seatIdsOf(organizer.eventId(), 1);
        Order order = buyAndPay(buyer, organizer.eventId(), seats);

        Refund started = refund(organizer.manager(), order.getId(), "Wrong date advertised.");
        deliverRefundWebhook(refundRefOf(started), "REFUNDED");

        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(availabilityOf(organizer.eventId(), seats.get(0)))
                .isEqualTo(SeatAvailability.AVAILABLE);

        // The provider changes its mind.
        deliverRefundWebhook(refundRefOf(started), "REFUND_FAILED", "expired_or_canceled_card");

        assertThat(refundsOf(organizer.manager(), order.getId()))
                .singleElement()
                .satisfies(reversed -> {
                    assertThat(reversed.getStatus()).isEqualTo(RefundStatus.REFUND_FAILED);
                    assertThat(reversed.getFailureReason()).isEqualTo("expired_or_canceled_card");
                });

        // The flag, because the record alone changes nothing: this Order is holding money
        // again and somebody has to see it (criteria 10 and 11).
        assertThat(orderOf(buyer, order.getId()).getRefundRequired()).isTrue();

        // And it has to be actionable. A flag on an Order nobody may refund is the failure
        // criterion 10 describes, and "already refunded" was exactly that answer.
        Refund second = refund(organizer.manager(), order.getId(), "Trying again after the reversal.");
        assertThat(second.getStatus()).isEqualTo(RefundStatus.REFUND_PENDING);

        // The seat is left where it was. It went back on sale when the refund settled and may
        // have been sold since; taking it from a second buyer to fix the first one's money is
        // the wrong trade.
        assertThat(availabilityOf(organizer.eventId(), seats.get(0)))
                .isEqualTo(SeatAvailability.AVAILABLE);
    }

    /** A failure for a refund that already failed is the ordinary re-delivery, not a reversal. */
    @Test
    @DisplayName("a repeated failure changes nothing")
    void aRepeatedFailureIsStillJustAFailure() {
        Organizer organizer = organizer();
        TokenPair buyer = signUp("repeat-buyer@example.com");
        Order order = buyAndPay(buyer, organizer.eventId(), seatIdsOf(organizer.eventId(), 1));

        Refund started = refund(organizer.manager(), order.getId(), "Wrong date.");
        deliverRefundWebhook(refundRefOf(started), "REFUND_FAILED", "Card account closed.");
        deliverRefundWebhook(refundRefOf(started), "REFUND_FAILED", "Card account closed.");

        assertThat(refundsOf(organizer.manager(), order.getId())).singleElement()
                .satisfies(it -> assertThat(it.getStatus()).isEqualTo(RefundStatus.REFUND_FAILED));
        assertThat(orderOf(buyer, order.getId()).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    private void deliverRefundWebhook(String providerRef, String status) {
        deliverRefundWebhook(providerRef, status, null);
    }

    private void deliverRefundWebhook(String providerRef, String status, String failureReason) {
        String body = failureReason == null
                ? """
                  {"eventId":"%s","providerRef":"%s","status":"%s"}"""
                        .formatted(UUID.randomUUID(), providerRef, status)
                : """
                  {"eventId":"%s","providerRef":"%s","status":"%s","failureReason":"%s"}"""
                        .formatted(UUID.randomUUID(), providerRef, status, failureReason);
        assertThat(deliverWebhookRaw(body,
                fakeProvider.signatureFor(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Settles every refund the system has started, the way a provider eventually would. */
    private void settleEveryRefund() {
        jdbc.queryForList("select provider_ref from refund where status = 'REFUND_PENDING'",
                String.class).forEach(ref -> deliverRefundWebhook(ref, "REFUNDED"));
    }
}
