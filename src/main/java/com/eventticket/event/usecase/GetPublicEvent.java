package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.event.repository.SeatCounts;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.shared.error.ApiException;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criterion 13: reachable by link whatever the Event's listed status, and by
 * anyone at all - there is no tenant on this request.
 *
 * <p>That works because the row-level security policies admit a published Event, its Venue and
 * its tiers to everyone (see {@code V4__venues_and_events.sql}). A Draft is not admitted, so a
 * draft belonging to some other Organization is invisible here; a draft of the caller's own
 * Organization would be visible, and is refused below. The public page is for published
 * events, whoever is asking.
 */
@Component
public class GetPublicEvent {

    private final EventRepository events;
    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final OrganizationRepository organizations;

    public GetPublicEvent(EventRepository events, EventSeatRepository seats,
                   PricingTierRepository tiers, VenueRepository venues,
                   OrganizationRepository organizations) {
        this.events = events;
        this.seats = seats;
        this.tiers = tiers;
        this.venues = venues;
        this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public PublicEventView get(UUID eventId) {
        Event event = events.findOrThrow(eventId);
        if (!event.isPublished()) {
            throw ApiException.notFound("Event");
        }

        Venue venue = venues.findOrThrow(event.venueId());
        String organizationName = organizations.findOrThrow(event.organizationId()).name();

        // The same counts the listing reports, from the same query, so the two pages cannot
        // disagree about an event a buyer is looking at on both.
        SeatCounts counted = SeatCounts.of(
                SeatCounts.asMap(seats.countSeats(List.of(eventId), Instant.now())), eventId);

        return new PublicEventView(event, organizationName, venue.name(), venue.city(),
                venue.timezone(), EventPricing.of(event, null, tiers.findByEventId(eventId)),
                counted.available(), counted.total());
    }
}
