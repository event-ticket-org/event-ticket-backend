package com.eventticket.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.EventSeat;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.OrderStatus;
import com.eventticket.api.model.RegisterRequest;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/** Knowledge base requirements/004. */
class BuyingTicketsTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("checkout holds the chosen seats and prices the order from the tiers")
    void checkoutTakesHoldsAndPricesTheOrder() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 2);

        Order order = checkout(buyer, eventId, seats).getBody();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.getTotal().getAmount()).isEqualTo(500_000L);   // two seats at 250,000
        assertThat(order.getSeats()).hasSize(2);
        assertThat(order.getHoldExpiresAt()).isAfter(OffsetDateTime.now());
        assertThat(order.getEventTitle()).isEqualTo("Live in Saigon");

        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.HELD);
    }

    @Test
    @DisplayName("a held seat cannot be taken by anyone else, and the refusal names it")
    void heldSeatsAreRefusedByName() {
        UUID eventId = publishedEvent();
        List<UUID> seats = seatIdsOf(eventId, 4);

        TokenPair first = signUp("first@example.com");
        checkout(first, eventId, List.of(seats.get(0), seats.get(1)));

        // The second buyer wants one seat that is taken and one that is not.
        TokenPair second = signUp("second@example.com");
        ResponseEntity<Error> refused = exchange(HttpMethod.POST, "/checkout", second,
                new com.eventticket.api.model.CheckoutRequest(eventId,
                        List.of(seats.get(1), seats.get(2))), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
        assertThat(refused.getBody().getDetails().get("seatIds").toString())
                .contains(seats.get(1).toString())
                .doesNotContain(seats.get(2).toString());

        // Nothing partial happened: the seat that was free is still free.
        assertThat(availabilityOf(eventId, seats.get(2))).isEqualTo(SeatAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("an unverified buyer is turned away before any seat is touched")
    void unverifiedBuyerCannotCheckOut() {
        UUID eventId = publishedEvent();
        List<UUID> seats = seatIdsOf(eventId, 1);

        // Registered, never followed the verification link.
        http.postForEntity("/auth/register",
                new RegisterRequest("unverified@example.com", "correct-horse-battery", "Unverified"),
                Void.class);
        TokenPair unverified = signIn("unverified@example.com");

        ResponseEntity<Error> refused = exchange(HttpMethod.POST, "/checkout", unverified,
                new com.eventticket.api.model.CheckoutRequest(eventId, seats), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("abandoning an order frees the seats at once rather than at expiry")
    void abandoningReleasesImmediately() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 2);
        Order order = checkout(buyer, eventId, seats).getBody();

        assertThat(exchange(HttpMethod.DELETE, "/orders/" + order.getId(), buyer, null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.AVAILABLE);

        // ...and someone else can have them straight away.
        TokenPair other = signUp("other@example.com");
        assertThat(checkout(other, eventId, seats).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a lapsed hold releases the seats without anything having to sweep them")
    void lapsedHoldsFreeTheSeats() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 1);
        Order order = checkout(buyer, eventId, seats).getBody();

        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.HELD);

        // Ten minutes pass. Nothing runs; the timestamp is simply in the past now.
        lapseHolds(order.getId());

        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.AVAILABLE);

        TokenPair other = signUp("other@example.com");
        assertThat(checkout(other, eventId, seats).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("seats held for a sold-out show cannot be bought once they are sold")
    void soldSeatsStaySold() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        List<UUID> seats = seatIdsOf(eventId, 1);

        Order paid = buyAndPay(buyer, eventId, seats);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(availabilityOf(eventId, seats.get(0))).isEqualTo(SeatAvailability.SOLD);

        TokenPair other = signUp("other@example.com");
        ResponseEntity<Error> refused = exchange(HttpMethod.POST, "/checkout", other,
                new com.eventticket.api.model.CheckoutRequest(eventId, seats), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
    }

    @Test
    @DisplayName("an unavailable seat is a conflict, not a validation failure")
    void statusCodesMatchTheContract() {
        UUID eventId = publishedEvent();
        List<UUID> seats = seatIdsOf(eventId, 1);
        checkout(signUp("first@example.com"), eventId, seats);

        assertThat(exchange(HttpMethod.POST, "/checkout", signUp("second@example.com"),
                new com.eventticket.api.model.CheckoutRequest(eventId, seats), Error.class)
                .getStatusCode()).isEqualTo(HttpStatusCode.valueOf(409));
    }

    // --- fixtures -------------------------------------------------------------------------

    /** Moves an Order's holds into the past, as ten minutes of a buyer's indecision would. */
    private void lapseHolds(UUID orderId) {
        jdbc.update("update event_seat set held_until = now() - interval '1 minute' "
                + "where held_by_order_id = ?", orderId);
        jdbc.update("update ticket_order set hold_expires_at = now() - interval '1 minute' "
                + "where id = ?", orderId);
    }

    private SeatAvailability availabilityOf(UUID eventId, UUID seatId) {
        return publicSeatMap(eventId).getSeats().stream()
                .filter(seat -> seat.getId().equals(seatId))
                .map(EventSeat::getAvailability)
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
