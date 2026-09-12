package com.eventticket.event.repository;

import com.eventticket.event.domain.PricingTier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PricingTierRepository extends MongoRepository<PricingTier, UUID> {

    public List<PricingTier> findByEventId(UUID eventId);

    public List<PricingTier> findByEventIdIn(List<UUID> eventIds);
}
