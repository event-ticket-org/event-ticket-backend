package com.eventticket.checkout.repository;

import com.eventticket.checkout.domain.Order;
import com.eventticket.shared.error.ApiException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends MongoRepository<Order, UUID> {

    /**
     * requirements/006 criterion 5: a buyer's Orders across every Organization, in one place.
     * Not narrowed by tenant here or in the policy - the buyer branch of {@code
     * ticket_order_access} is what makes this legible at all, since a buyer usually has no
     * active Organization.
     */
    @Query("""
           select o from Order o
           where o.buyerUserId = :buyerUserId
             and (o.createdAt < :cursorAt
                  or (o.createdAt = :cursorAt and o.id < :cursorId))
           order by o.createdAt desc, o.id desc
           """)
    public List<Order> findPageForBuyer(@Param("buyerUserId") UUID buyerUserId,
                                        @Param("cursorAt") Instant cursorAt,
                                        @Param("cursorId") UUID cursorId,
                                        Pageable page);

    /**
     * The organizer's view of one Event's Orders (requirements/008 criterion 10).
     *
     * <p>Not narrowed by organization here: the Event already belongs to one, and the
     * {@code ticket_order} policy has already refused every row that does not. Adding a
     * predicate would make this look like the thing keeping tenants apart, which it is not.
     *
     * <p>Both filters are expressed as the set of values they admit rather than as a null to
     * test for. Postgres cannot infer the type of a bare parameter in {@code ? is null}, and
     * the widest value is a better plan besides.
     */
    @Query("""
           select o from Order o
           where o.eventId = :eventId
             and o.status in :statuses
             and o.refundRequired in :refundRequired
             and (o.createdAt < :cursorAt
                  or (o.createdAt = :cursorAt and o.id < :cursorId))
           order by o.createdAt desc, o.id desc
           """)
    public List<Order> findPageForEvent(@Param("eventId") UUID eventId,
                                        @Param("statuses") Collection<Order.Status> statuses,
                                        @Param("refundRequired") Collection<Boolean> refundRequired,
                                        @Param("cursorAt") Instant cursorAt,
                                        @Param("cursorId") UUID cursorId,
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
