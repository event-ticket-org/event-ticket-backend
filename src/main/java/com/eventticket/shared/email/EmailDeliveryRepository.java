package com.eventticket.shared.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailDeliveryRepository extends MongoRepository<EmailDelivery, UUID> {

    public List<EmailDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            EmailDelivery.Status status, Instant now, Pageable page);
}
