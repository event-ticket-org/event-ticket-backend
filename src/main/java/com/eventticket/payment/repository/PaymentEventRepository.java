package com.eventticket.payment.repository;

import com.eventticket.payment.domain.PaymentEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    public boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
