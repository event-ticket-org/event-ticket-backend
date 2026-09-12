package com.eventticket.checkout.repository;

import com.eventticket.checkout.domain.Order;
import com.eventticket.shared.error.ApiException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface OrderRepository extends MongoRepository<Order, UUID> {

    /**
     * requirements/006 criterion 5: a buyer's Orders across every Organization, in one place.
     * Not narrowed by tenant here or in the policy - the buyer branch of {@code
     * ticket_order_access} is what makes this legible at all, since a buyer usually has no
     * active Organization.
     *
     * <p>The keyset cursor translates directly: the SQL disjunction becomes an {@code $or}, and
     * paging still fetches {@code limit + 1} rather than counting. One difference is worth
     * knowing and is invisible until it bites - <strong>the tie-break compares UUIDs, and
     * MongoDB compares them as BSON binary where Postgres compares them as its own uuid
     * type.</strong> The two orderings are not the same. Neither is meaningful, which is why
     * this works: a cursor needs an order that is total and stable, not one that means
     * anything. A cursor issued by the Postgres build would resume in the wrong place here,
     * so cursors do not survive the migration - and nothing says they should, since they are
     * opaque by contract.
     */
    @Query(value = """
           { 'buyerUserId': ?0,
             '$or': [ { 'createdAt': { '$lt': ?1 } },
                      { 'createdAt': ?1, '_id': { '$lt': ?2 } } ] }
           """,
           sort = "{ 'createdAt': -1, '_id': -1 }")
    public List<Order> findPageForBuyer(UUID buyerUserId,
                                        Instant cursorAt,
                                        UUID cursorId,
                                        Pageable page);

    /**
     * The organizer's view of one Event's Orders (requirements/008 criterion 10).
     *
     * <p>Not narrowed by organization here: the Event already belongs to one, and the
     * {@code ticket_order} policy has already refused every row that does not. Adding a
     * predicate would make this look like the thing keeping tenants apart, which it is not.
     *
     * <p>Both filters are expressed as the set of values they admit rather than as a null to
     * test for. That was written because Postgres cannot infer the type of a bare parameter in
     * {@code ? is null} - a reason that has gone. The shape stays anyway: it is still one query
     * plan instead of two, and it is still the clearer way to say "any of these".
     */
    @Query(value = """
           { 'eventId': ?0,
             'status': { '$in': ?1 },
             'refundRequired': { '$in': ?2 },
             '$or': [ { 'createdAt': { '$lt': ?3 } },
                      { 'createdAt': ?3, '_id': { '$lt': ?4 } } ] }
           """,
           sort = "{ 'createdAt': -1, '_id': -1 }")
    public List<Order> findPageForEvent(UUID eventId,
                                        Collection<Order.Status> statuses,
                                        Collection<Boolean> refundRequired,
                                        Instant cursorAt,
                                        UUID cursorId,
                                        Pageable page);

    /** Every paid Order on an Event, which is what a cancellation has to give back. */
    public List<Order> findByEventIdAndStatus(UUID eventId, Order.Status status);

    /**
     * The count behind {@code Event.refundRequiredCount}. It is on the Event because that is
     * where an organizer looks, and a flag nobody can find is the same as no flag.
     */
    public long countByEventIdAndRefundRequiredTrue(UUID eventId);

    public default Order findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Order"));
    }
}
