package com.eventticket.payment.repository;

import com.eventticket.payment.domain.PaymentEvent;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentEventRepository extends MongoRepository<PaymentEvent, UUID> {

    public boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
