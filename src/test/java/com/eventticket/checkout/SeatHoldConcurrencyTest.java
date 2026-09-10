package com.eventticket.checkout;

import java.time.Instant;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.CheckoutRequest;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * requirements/004 criterion 10 and KB invariant 5.
 *
 * <p>Real threads against the running server, all released at the same instant by a latch. A
 * simulated race - calling the use case twice in a loop - would pass against an implementation
 * that reads availability and then writes it, which is exactly the implementation this exists
 * to rule out. nfr.md puts 500 concurrent buyers on an on-sale spike, so the race is the normal
 * case rather than the exception.
 */
class SeatHoldConcurrencyTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);
    private static final int BUYERS = 12;

    @Test
    @DisplayName("twelve buyers, one seat, at the same instant: exactly one gets it")
    void exactlyOneBuyerWinsASeat() throws Exception {
        UUID eventId = publishedEvent();
        UUID contested = seatIdsOf(eventId, 1).get(0);

        List<TokenPair> buyers = signUpMany(BUYERS);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        raceFor(buyers, eventId, List.of(contested), created, refused);

        assertThat(created.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(BUYERS - 1);

        // And the database agrees: one hold, on one seat, for one order.
        assertThat(countIn("eventSeat", Criteria.where("_id").is(contested)
                .and("heldUntil").gt(Instant.now()))).isEqualTo(1L);
        // count(distinct ...) has no direct counterpart; distinct returns the values and the
        // count is taken here.
        assertThat(mongo.findDistinct(
                Query.query(Criteria.where("_id").is(contested)), "heldByOrderId",
                "eventSeat", Object.class)).hasSize(1);
    }

    @Test
    @DisplayName("overlapping selections do not deadlock, and no seat is held twice")
    void overlappingSelectionsResolveWithoutDeadlock() throws Exception {
        UUID eventId = publishedEvent();
        List<UUID> seats = seatIdsOf(eventId, 6);

        // Each buyer asks for the same six seats in a different order. Locking them in request
        // order rather than a fixed one is what would deadlock here.
        List<TokenPair> buyers = signUpMany(BUYERS);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(BUYERS)) {
            List<? extends Future<?>> attempts = buyers.stream().map(buyer -> pool.submit(() -> {
                List<UUID> shuffled = new java.util.ArrayList<>(seats);
                java.util.Collections.shuffle(shuffled);
                start.await();
                var response = exchange(HttpMethod.POST, "/checkout", buyer,
                        new CheckoutRequest(eventId, shuffled), Object.class);
                (response.getStatusCode() == HttpStatus.CREATED ? created : refused).incrementAndGet();
                return null;
            })).toList();

            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(created.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(BUYERS - 1);
        assertThat(countIn("eventSeat", Criteria.where("eventId").is(eventId)
                .and("heldUntil").gt(Instant.now()))).isEqualTo(6L);
    }

    private void raceFor(List<TokenPair> buyers, UUID eventId, List<UUID> seats,
                         AtomicInteger created, AtomicInteger refused) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(buyers.size())) {
            List<? extends Future<?>> attempts = buyers.stream().map(buyer -> pool.submit(() -> {
                start.await();
                var response = exchange(HttpMethod.POST, "/checkout", buyer,
                        new CheckoutRequest(eventId, seats), Object.class);
                (response.getStatusCode() == HttpStatus.CREATED ? created : refused).incrementAndGet();
                return null;
            })).toList();

            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        }
    }

    private List<TokenPair> signUpMany(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> signUp("buyer" + i + "@example.com"))
                .toList();
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
