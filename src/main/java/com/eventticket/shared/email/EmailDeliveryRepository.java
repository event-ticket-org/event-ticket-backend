package com.eventticket.shared.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {

    public List<EmailDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            EmailDelivery.Status status, Instant now, Pageable page);
}
