package com.eventticket.admission.repository;

import com.eventticket.admission.domain.Scan;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ScanRepository extends MongoRepository<Scan, UUID> {

    public List<Scan> findByEventIdOrderByOccurredAtDesc(UUID eventId);

    public long countByEventId(UUID eventId);
}
