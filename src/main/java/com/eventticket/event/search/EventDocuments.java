package com.eventticket.event.search;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Events as documents, a batch at a time.
 *
 * <p><strong>Why this is not {@code PublicEventViews}</strong>, which assembles nearly the same
 * thing: that one computes seat counts, and a document deliberately has none. Reusing it would
 * run {@code countSeats} over every published Event on every nightly rebuild to produce two
 * numbers that are then discarded - work whose only purpose is to be thrown away, and the one
 * shape of waste that grows exactly as fast as the catalogue does.
 *
 * <p>It needs no display names either. A document carries {@code citySlug} and
 * {@code categorySlug} because those are what a filter matches; the names beside them come from
 * the vocabulary the client already holds.
 */
@Component
public class EventDocuments {

    private final VenueRepository venues;
    private final OrganizationRepository organizations;
    private final PricingTierRepository tiers;

    public EventDocuments(VenueRepository venues, OrganizationRepository organizations,
                   PricingTierRepository tiers) {
        this.venues = venues;
        this.organizations = organizations;
        this.tiers = tiers;
    }

    public List<EventDocument> of(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }

        Map<UUID, Venue> venuesById = venues
                .findByIdIn(events.stream().map(Event::venueId).distinct().toList())
                .stream().collect(Collectors.toMap(Venue::id, venue -> venue));
        Map<UUID, String> organizationNames = organizations
                .findAllById(events.stream().map(Event::organizationId).distinct().toList())
                .stream().collect(Collectors.toMap(Organization::id, Organization::name));
        Map<UUID, List<PricingTier>> tiersByEvent = tiers
                .findByEventIdIn(events.stream().map(Event::id).toList())
                .stream().collect(Collectors.groupingBy(PricingTier::eventId));

        return events.stream().map(event -> {
            Venue venue = venuesById.get(event.venueId());
            EventPricing pricing = EventPricing.of(event, null,
                    tiersByEvent.getOrDefault(event.id(), List.of()));
            return new EventDocument(
                    event.id(),
                    event.title(),
                    event.description(),
                    organizationNames.get(event.organizationId()),
                    venue.name(),
                    venue.citySlug(),
                    event.categorySlug(),
                    event.startsAt(),
                    pricing.cheapest().map(money -> money.amount()).orElse(null),
                    event.coverImageUrl(),
                    event.coverImageAlt(),
                    event.coverImageRenderings().stream()
                            .map(rendering -> new EventDocument.CoverSize(
                                    rendering.url(), rendering.width()))
                            .toList());
        }).toList();
    }
}
