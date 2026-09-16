package com.eventticket.event.support;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventCategory;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.repository.EventCategoryRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.event.repository.SeatCounts;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.venue.domain.City;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.CityRepository;
import com.eventticket.venue.repository.VenueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Everything a public Event card needs beside the Event, fetched a page at a time.
 *
 * <p>An Event on its own is not something anybody can read: the Organization's name, the
 * Venue's name and city, the Category's name, the cheapest price and how many seats are left
 * all live somewhere else. Three use cases now need exactly that set - the listing, the curated
 * row and the ranked row - and they differ only in <em>which</em> Events they start from.
 *
 * <p><strong>Why this is not the helper ADR-0001 forbids.</strong> The rule is that shared
 * logic goes down into the domain or the repository and never sideways into a service between
 * use cases. This holds no logic to share: there is not a decision in it, and nothing here can
 * refuse anything. It is the read model's assembly - six lookups keyed by id - and it lives in
 * {@code support} for the reason that package exists, as feature-local infrastructure.
 *
 * <p>The alternative was the same twenty lines in three use cases, and what that costs is
 * specific rather than aesthetic: the listing and a rail showing different seat counts for the
 * same Event, on the same screen, because one of them was updated and the others were not.
 *
 * <p>Every lookup is by the whole page rather than per row. That is the difference between
 * this and a mapped association, and it is deliberate - see {@code NoOrmAssociationsTest}.
 */
@Component
public class PublicEventViews {

    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final OrganizationRepository organizations;
    private final EventCategoryRepository categories;
    private final CityRepository cities;

    public PublicEventViews(EventSeatRepository seats, PricingTierRepository tiers,
                     VenueRepository venues, OrganizationRepository organizations,
                     EventCategoryRepository categories, CityRepository cities) {
        this.seats = seats;
        this.tiers = tiers;
        this.venues = venues;
        this.organizations = organizations;
        this.categories = categories;
        this.cities = cities;
    }

    /** In the order given, because the caller's order is the answer - ranked, curated or by time. */
    public List<PublicEventView> of(List<Event> events, Instant now) {
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
        Map<UUID, SeatCounts> counted = SeatCounts.asMap(
                seats.countSeats(events.stream().map(Event::id).toList(), now));

        // Both vocabularies whole. They are a handful of immutable rows each, so reading all of
        // one beats building a predicate for the three slugs a page happens to mention.
        Map<String, String> cityNames = cities.findAll().stream()
                .collect(Collectors.toMap(City::slug, City::name));
        Map<String, String> categoryNames = categories.findAll().stream()
                .collect(Collectors.toMap(EventCategory::slug, EventCategory::name));

        return events.stream().map(event -> {
            Venue venue = venuesById.get(event.venueId());
            return new PublicEventView(event, organizationNames.get(event.organizationId()),
                    venue.name(), cityNames.get(venue.citySlug()), venue.citySlug(),
                    categoryNames.get(event.categorySlug()), venue.timezone(),
                    EventPricing.of(event, null, tiersByEvent.getOrDefault(event.id(), List.of())),
                    SeatCounts.of(counted, event.id()).available(),
                    SeatCounts.of(counted, event.id()).total());
        }).toList();
    }
}
