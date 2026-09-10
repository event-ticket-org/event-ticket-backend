package com.eventticket.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.CheckoutRequest;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
 *
 * <h2>Why the losers' status codes are asserted and not just counted</h2>
 *
 * <p>This test used to say {@code (status == CREATED ? created : refused).incrementAndGet()},
 * so <strong>every outcome that was not a 201 counted as a civil refusal</strong> - a 500
 * included. It stayed green through the entire MongoDB migration while eleven of twelve buyers
 * received {@code 500 VALIDATION_FAILED, "The request could not be completed."}
 *
 * <p>The cause is the difference this whole migration is about. Postgres held the contested
 * rows under {@code SELECT … FOR UPDATE}: the eleven losers <em>blocked</em>, woke when the
 * winner committed, re-evaluated, and were refused with a 409 naming the seats. A MongoDB
 * transaction is optimistic, so the same contention raises
 * {@code WriteConflict / TransientTransactionError} and the driver expects the caller to retry
 * the whole transaction. Nothing retried, so the conflict reached the buyer as a server error.
 *
 * <p>Counting outcomes could not see that, and neither could a table comparing the two builds
 * on how many buyers succeeded - both said "one winner" and both were right. <strong>The
 * defect was entirely in what the losers were told.</strong> Assert the refusal, not the
 * absence of success.
 */
class SeatHoldConcurrencyTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);
    private static final int BUYERS = 12;

    /** One buyer's outcome: the status the door of the shop gave them, and the error code with it. */
    private record Attempt(HttpStatus status, String code) {}

    @Test
    @DisplayName("twelve buyers, one seat, at the same instant: exactly one gets it")
    void exactlyOneBuyerWinsASeat() throws Exception {
        UUID eventId = publishedEvent();
        UUID contested = seatIdsOf(eventId, 1).get(0);

        List<Attempt> attempts = race(signUpMany(BUYERS), buyer -> List.of(contested), eventId);

        assertWonByExactlyOne(attempts);

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
        List<Attempt> attempts = race(signUpMany(BUYERS), buyer -> {
            List<UUID> shuffled = new java.util.ArrayList<>(seats);
            Collections.shuffle(shuffled);
            return shuffled;
        }, eventId);

        assertWonByExactlyOne(attempts);
        assertThat(countIn("eventSeat", Criteria.where("eventId").is(eventId)
                .and("heldUntil").gt(Instant.now()))).isEqualTo(6L);
    }

    /**
     * One winner, and every loser told something a buyer can act on.
     *
     * <p>criterion 6: a refusal names the seats that went, so the client keeps the rest of the
     * selection. A 500 says "the request could not be completed", which is the one answer that
     * helps nobody - it is indistinguishable from the site being broken, and a client is right
     * to offer a retry that will fail the same way.
     */
    private static void assertWonByExactlyOne(List<Attempt> attempts) {
        assertThat(attempts).hasSize(BUYERS);
        assertThat(attempts).filteredOn(a -> a.status() == HttpStatus.CREATED).hasSize(1);
        assertThat(attempts).filteredOn(a -> a.status() != HttpStatus.CREATED)
                .allSatisfy(loser -> {
                    assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(loser.code()).isEqualTo("SEATS_UNAVAILABLE");
                });
    }

    private List<Attempt> race(List<TokenPair> buyers, Function<TokenPair, List<UUID>> selection,
                               UUID eventId) throws Exception {
        List<Attempt> outcomes = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(buyers.size())) {
            List<? extends Future<?>> attempts = buyers.stream().map(buyer -> pool.submit(() -> {
                List<UUID> seats = selection.apply(buyer);
                start.await();
                var response = exchange(HttpMethod.POST, "/checkout", buyer,
                        new CheckoutRequest(eventId, seats), Object.class);
                outcomes.add(new Attempt(HttpStatus.valueOf(response.getStatusCode().value()),
                        codeOf(response.getBody())));
                return null;
            })).toList();

            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        }
        return List.copyOf(outcomes);
    }

    /** The error envelope's code, or null for a body that is an Order rather than an error. */
    private static String codeOf(Object body) {
        return body instanceof Map<?, ?> map && map.get("code") instanceof String code ? code : null;
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
