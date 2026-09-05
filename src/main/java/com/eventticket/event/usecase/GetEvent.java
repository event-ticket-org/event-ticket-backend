package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A manager's view of an Event: prices, tiers and how many have sold. Not a Gate Staff view -
 * see the check below.
 */
@Component
public class GetEvent {

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;

    public GetEvent(EventRepository events, PricingTierRepository tiers, VenueRepository venues,
             Managers managers) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
    }

    @Transactional(readOnly = true)
    public EventDetail get(UUID eventId) {
        UUID organizationId = TenantContext.requireOrganizationId();

        // KB invariant 3: Gate Staff may read only what is needed to scan, and sales figures are
        // never visible to them. An Event carries its pricing tiers and a sold count, so this is
        // refused rather than redacted - a second, poorer Event schema would be worse than an
        // honest no, and Gate Staff have the scanner and the public page.
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);

        // The Venue is read only for a Draft, whose tiers still track the room's map. A
        // published Event answers from its own rows; reading the Venue for one would be
        // asking a question whose answer cannot matter.
        SeatMapDocument map = event.isPublished() ? null : venues.findOrThrow(event.venueId()).seatMap();

        return EventDetail.of(event, EventPricing.of(event, map, tiers.findByEventId(eventId)));
    }
}
