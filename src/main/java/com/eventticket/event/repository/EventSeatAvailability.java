package com.eventticket.event.repository;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventSeat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * The three transitions a seat's availability can make.
 *
 * <h2>What these used to be</h2>
 *
 * <p>Four {@code SECURITY DEFINER} SQL functions, for two reasons that both disappear here. A
 * buyer must be able to hold a seat in an Organization they are not a member of, which the
 * {@code event_seat} policy rightly refused - so the write happened in a definer function, a
 * far narrower grant than widening the policy would have been. <strong>There is no policy to
 * refuse anything now, so there is nothing to elevate past.</strong> The grant disappears
 * because the protection it worked around disappeared, which is not an improvement.
 *
 * <p>The second reason was locking, and that one has no replacement at all.
 *
 * <h2>Losing SELECT … FOR UPDATE</h2>
 *
 * <p>{@code hold_seats} selected the wanted seats {@code FOR UPDATE ORDER BY id} - taking a
 * pessimistic lock on every row, in a fixed order so two buyers could not deadlock - and only
 * then wrote. A second buyer for the same seat <em>blocked</em>, woke when the first committed,
 * re-evaluated the predicate, and found the seat taken. One clean winner, one clean loser.
 *
 * <p>MongoDB has no pessimistic lock to take. What it has is the guarantee that a write to a
 * single document is atomic, so the condition moves into the filter: a seat is claimed by an
 * update that will only match a seat nobody else has claimed. <strong>Per seat, that is exactly
 * as correct as the lock was</strong> - there is no read-then-write gap, so no check-then-act
 * race.
 *
 * <p>The loss is across seats. {@code updateMulti} is atomic per document and not across them,
 * so an order for three seats can win two and lose one. Postgres could not do that: it held all
 * three locks or waited. So this compensates - it releases what it won and reports the
 * shortfall - and the consequence is visible under contention:
 *
 * <p><strong>Where Postgres produced one winner and one loser, MongoDB can produce two
 * losers.</strong> Two buyers overlapping on a middle seat can each take part of their
 * selection, each find themselves short, and each roll back. Neither is served, and the seats
 * are free again for whoever asks next. Nothing is corrupted and nobody is double-sold - the
 * invariant holds - but throughput under contention is strictly worse, and it degrades exactly
 * when the system is busiest, which is the property worth knowing.
 */
@Repository
public class EventSeatAvailability {

    private final MongoTemplate mongo;

    public EventSeatAvailability(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * The seats that were free and are now held. Anything missing was taken by someone else.
     *
     * <p>The availability test is the filter, so each seat is won or lost atomically. Note that
     * {@code now} is computed here, in the JVM, where {@code hold_seats} used the database's
     * {@code now()} inside the transaction - so hold expiry is decided by the application's
     * clock, and clock skew across application instances becomes a correctness concern that it
     * was not before.
     */
    public List<UUID> hold(UUID eventId, List<UUID> seatIds, UUID orderId, Instant until) {
        Instant now = Instant.now();
        Query free = new Query(Criteria.where("_id").in(seatIds)
                .and("eventId").is(eventId)
                .and("forSale").is(true)
                .and("soldAt").is(null)
                .orOperator(Criteria.where("heldUntil").is(null),
                        Criteria.where("heldUntil").lte(now)));

        mongo.updateMulti(free, new Update().set("heldUntil", until).set("heldByOrderId", orderId),
                EventSeat.class);

        // Read back rather than trusting the modified count, which says how many seats were
        // won but not which. Safe to do as a second query only because a seat this order now
        // holds cannot be taken from it: every competing filter requires an expired hold.
        return idsOf(new Query(Criteria.where("_id").in(seatIds).and("heldByOrderId").is(orderId)));
    }

    /** requirements/004 criterion 11: abandoning releases the seats at once, not at expiry. */
    public int release(UUID orderId) {
        return (int) mongo.updateMulti(
                new Query(Criteria.where("heldByOrderId").is(orderId).and("soldAt").is(null)),
                new Update().unset("heldUntil").unset("heldByOrderId"),
                EventSeat.class).getModifiedCount();
    }

    /**
     * requirements/008 criterion 5: a refunded Order's seats go back on sale, if there is still
     * a sale to go back into. Returns how many actually moved, which is zero for a cancelled or
     * closed Event and is not a failure - there is simply nothing to sell.
     *
     * <p>{@code release_sold_seats} did this in one statement, joining {@code event_seat} to
     * {@code event} to test {@code e.status = 'PUBLISHED'}. That join has to happen here now:
     * find the seats, look up their Event, then write. <strong>Three round trips where there
     * was one statement, and the Event's status is read in a different instant from the write
     * that depends on it</strong> - an Event cancelled in between would have its seats put back
     * on sale by a check that was true when it was made.
     */
    public int releaseSold(UUID orderId) {
        List<EventSeat> sold = mongo.find(
                new Query(Criteria.where("heldByOrderId").is(orderId).and("soldAt").ne(null)),
                EventSeat.class);
        if (sold.isEmpty()) {
            return 0;
        }
        Event event = mongo.findById(sold.get(0).eventId(), Event.class);
        if (event == null || event.status() != Event.Status.PUBLISHED) {
            return 0;
        }
        return (int) mongo.updateMulti(
                new Query(Criteria.where("_id").in(sold.stream().map(EventSeat::id).toList())),
                new Update().set("soldAt", null),
                EventSeat.class).getModifiedCount();
    }

    /**
     * The seats whose hold was still alive and are now sold.
     *
     * <p>{@code heldByOrderId} is deliberately <em>not</em> cleared on sale, where
     * {@code sell_seats} cleared it. It is the only remaining way to ask "which seats did this
     * Order get", and MongoDB cannot return the documents it just modified from the same
     * operation the way {@code RETURNING} could. Selling first and reading second is the safe
     * order: reading first would be a check-then-act, since a hold can lapse between the two.
     */
    public List<UUID> sell(UUID orderId) {
        Instant now = Instant.now();
        mongo.updateMulti(
                new Query(Criteria.where("heldByOrderId").is(orderId)
                        .and("soldAt").is(null)
                        .and("heldUntil").gt(now)),
                new Update().set("soldAt", now).unset("heldUntil"),
                EventSeat.class);

        return idsOf(new Query(Criteria.where("heldByOrderId").is(orderId).and("soldAt").ne(null)));
    }

    private List<UUID> idsOf(Query query) {
        return mongo.find(query, EventSeat.class).stream().map(EventSeat::id).toList();
    }
}
