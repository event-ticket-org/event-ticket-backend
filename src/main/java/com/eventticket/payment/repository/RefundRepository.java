package com.eventticket.payment.repository;

import com.eventticket.payment.domain.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * No tenant narrows these, for the same reason as {@link PaymentSessionRepository}: the
 * callback that settles a refund arrives with no tenant at all. Everything that reaches a
 * refund from outside a callback goes through its Order first, and {@code ticket_order} is
 * scoped - so the check happens where the caller is known rather than here.
 */
public interface RefundRepository extends MongoRepository<Refund, UUID> {

    public Optional<Refund> findByProviderAndProviderRef(String provider, String providerRef);

    public List<Refund> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    public List<Refund> findByOrderIdIn(List<UUID> orderIds);

    /**
     * A refund that has not failed, if there is one. The partial unique index in {@code V7}
     * is what makes "one" true concurrently; this is how a caller learns it without provoking
     * a constraint violation to find out.
     */
    public Optional<Refund> findByOrderIdAndStatusNot(UUID orderId, Refund.Status status);
}
