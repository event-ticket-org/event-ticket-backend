package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criterion 1. The Event starts in Draft with no seats of its own: while it
 * is a Draft it shows the Venue's current Seat Map and reflects edits to it (criterion 2),
 * which it does by reading that map rather than by keeping a copy in step with it.
 *
 * <p>An unpriced Pricing Tier row is created for each tier the map names, so that a manager
 * opening a new Event sees what still has to be priced instead of an empty list.
 */
@Component
public class CreateEvent {

    private static final Logger log = LoggerFactory.getLogger(CreateEvent.class);

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;

    public CreateEvent(EventRepository events, PricingTierRepository tiers,
                VenueRepository venues, Managers managers) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
    }

    @Transactional
    public EventDetail create(String title, String description, String coverImageUrl,
                              UUID venueId, Instant startsAt, boolean listed) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        SeatMapDocument map = venues.findOrThrow(venueId)
                .requireBelongsTo(organizationId)
                .seatMap();

        Event event = events.save(new Event(
                organizationId, venueId, title, description, coverImageUrl, startsAt, listed));

        List<PricingTier> created = tiers.saveAll(map.tierNames().stream()
                .map(name -> new PricingTier(organizationId, event.id(), name))
                .toList());

        log.info("Created event eventId={} venueId={} tiers={}", event.id(), venueId, created.size());
        return EventDetail.of(event, EventPricing.of(map.tierNames(), created));
    }
}
