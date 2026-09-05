package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.money.Money;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criteria 3 and 10.
 *
 * <p>After publish a price change is allowed and applies only to later sales. Nothing here
 * needs to arrange that: a Ticket and a Seat Hold capture their price when they are created,
 * so an edit cannot reach back to one that already exists (KB invariant 10).
 */
@Component
public class SetPricingTiers {

    private static final Logger log = LoggerFactory.getLogger(SetPricingTiers.class);

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;
    private final AuditTrail audit;

    public SetPricingTiers(EventRepository events, PricingTierRepository tiers, VenueRepository venues,
                    Managers managers, AuditTrail audit) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
        this.audit = audit;
    }

    @Transactional
    public EventPricing set(UUID eventId, Map<String, Money> prices) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        SeatMapDocument map = event.isPublished() ? null : venues.findOrThrow(event.venueId()).seatMap();

        List<PricingTier> rows = tiers.findByEventId(eventId);
        Map<String, PricingTier> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.name(), row));

        EventPricing existing = EventPricing.of(event, map, rows);
        List<String> namesInUse = existing.tiers().stream().map(EventPricing.Tier::name).toList();

        prices.forEach((name, price) -> {
            if (!namesInUse.contains(name)) {
                // Pricing a tier no seat belongs to is almost always a typo in the tier name,
                // and silently storing it would leave the manager waiting for a price to appear.
                throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                        "No seat belongs to the pricing tier \"" + name + "\".",
                        Map.of("tierName", name));
            }
            byName.computeIfAbsent(name, n -> new PricingTier(organizationId, eventId, n)).priceAt(price);
        });

        List<PricingTier> saved = tiers.saveAll(byName.values());
        audit.record(organizationId, AuditTrail.EVENT_PRICES_CHANGED, event.title());
        log.info("Priced event eventId={} tiers={}", eventId, prices.keySet());

        return EventPricing.of(event, map, saved);
    }
}
