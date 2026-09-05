package com.eventticket.payment.repository;

import com.eventticket.payment.domain.PaymentSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No tenant narrows these. {@code payment_session} carries no row-level security, because the
 * webhook that reads it has no tenant to be narrowed by - see the entity for why that is safe.
 */
public interface PaymentSessionRepository extends JpaRepository<PaymentSession, UUID> {

    public Optional<PaymentSession> findByProviderAndProviderRef(String provider, String providerRef);
}
