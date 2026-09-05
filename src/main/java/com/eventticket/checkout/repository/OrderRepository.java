package com.eventticket.checkout.repository;

import com.eventticket.checkout.domain.Order;
import com.eventticket.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

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

    public default Order findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Order"));
    }
}
