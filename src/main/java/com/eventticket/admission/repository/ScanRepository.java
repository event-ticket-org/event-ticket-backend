package com.eventticket.admission.repository;

import com.eventticket.admission.domain.Scan;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepository extends JpaRepository<Scan, UUID> {

    public List<Scan> findByEventIdOrderByOccurredAtDesc(UUID eventId);

    public long countByEventId(UUID eventId);
}
