package com.eventticket.event.repository;

import com.eventticket.event.domain.PricingTier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingTierRepository extends JpaRepository<PricingTier, UUID> {

    public List<PricingTier> findByEventId(UUID eventId);

    public List<PricingTier> findByEventIdIn(List<UUID> eventIds);
}
