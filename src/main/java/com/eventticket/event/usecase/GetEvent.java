package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GetEvent {

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;

    public GetEvent(EventRepository events, PricingTierRepository tiers, VenueRepository venues) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
    }

    @Transactional(readOnly = true)
    public EventDetail get(UUID eventId) {
        Event event = events.findOrThrow(eventId)
                .requireBelongsTo(TenantContext.requireOrganizationId());

        // The Venue is read only for a Draft, whose tiers still track the room's map. A
        // published Event answers from its own rows; reading the Venue for one would be
        // asking a question whose answer cannot matter.
        SeatMapDocument map = event.isPublished() ? null : venues.findOrThrow(event.venueId()).seatMap();

        return EventDetail.of(event, EventPricing.of(event, map, tiers.findByEventId(eventId)));
    }
}
