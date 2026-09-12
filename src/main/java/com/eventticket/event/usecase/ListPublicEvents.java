package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.event.repository.SeatCounts;
import com.eventticket.event.support.PageCursor;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.shared.page.Paged;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/009: published, listed Events of approved Organizations that have not started,
 * ordered by start time. No ranking, no relevance, no personalization - the order is the
 * order, and a buyer who scrolls sees every event once.
 *
 * <p>This endpoint is the reason a Venue's city is a column of its own: filtering on a
 * free-text address is not something a database can do.
 */
@Component
public class ListPublicEvents {

    private final EventRepository events;
    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final OrganizationRepository organizations;

    public ListPublicEvents(EventRepository events, EventSeatRepository seats,
                     PricingTierRepository tiers, VenueRepository venues,
                     OrganizationRepository organizations) {
        this.events = events;
        this.seats = seats;
        this.tiers = tiers;
        this.venues = venues;
        this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public Paged<PublicEventView> list(String query, String city, Instant startsAfter,
                                       Instant startsBefore, int limit, String cursor) {
        PageCursor from = PageCursor.decode(cursor, PageCursor.FIRST_ASCENDING);
        PageRequest page = PageRequest.ofSize(limit + 1);
        Instant now = Instant.now();
        Instant after = PageCursor.orBeginning(startsAfter);
        Instant before = PageCursor.orEndOfTime(startsBefore);
        String title = titlePattern(query);

        // One method where there were two. The city variant was the same query with one extra
        // `in`, duplicated only because the two were separate JPQL strings; criteria composed
        // in Java can simply omit the clause, so a null `venueIds` means "every venue".
        List<UUID> venueIds = null;
        if (city != null && !city.isBlank()) {
            venueIds = venues.findIdsByCity(city);
            // An "in ()" with nothing in it is not a query worth sending.
            if (venueIds.isEmpty()) {
                return Paged.lastPage(List.of());
            }
        }

        List<Event> found = events.findPublicPage(now, Event.Status.PUBLISHED,
                Organization.Status.APPROVED, venueIds, after, before, title,
                from.at(), from.id(), limit + 1);

        boolean more = found.size() > limit;
        List<Event> visible = more ? found.subList(0, limit) : found;
        if (visible.isEmpty()) {
            return Paged.lastPage(List.of());
        }

        Map<UUID, Venue> venuesById = venues
                .findByIdIn(visible.stream().map(Event::venueId).distinct().toList())
                .stream().collect(Collectors.toMap(Venue::id, v -> v));
        Map<UUID, String> organizationNames = organizations
                .findAllById(visible.stream().map(Event::organizationId).distinct().toList())
                .stream().collect(Collectors.toMap(Organization::id, Organization::name));
        Map<UUID, List<PricingTier>> tiersByEvent = tiers
                .findByEventIdIn(visible.stream().map(Event::id).toList())
                .stream().collect(Collectors.groupingBy(PricingTier::eventId));
        Map<UUID, SeatCounts> counted = SeatCounts.asMap(
                seats.countSeats(visible.stream().map(Event::id).toList(), now));

        List<PublicEventView> items = visible.stream().map(event -> {
            Venue venue = venuesById.get(event.venueId());
            return new PublicEventView(event, organizationNames.get(event.organizationId()),
                    venue.name(), venue.city(), venue.timezone(),
                    EventPricing.of(event, null, tiersByEvent.getOrDefault(event.id(), List.of())),
                    SeatCounts.of(counted, event.id()).available(),
                    SeatCounts.of(counted, event.id()).total());
        }).toList();

        Event last = visible.get(visible.size() - 1);
        return new Paged<>(items, more ? PageCursor.encode(last.startsAt(), last.id()) : null);
    }

    /**
     * The text filter as a LIKE pattern, or {@code %} when there is nothing to filter by.
     *
     * <p>An absent filter is the widest value rather than a null and a second query shape -
     * the same idiom the date bounds use, and for the same reason (EventRepository).
     *
     * <p>The wildcards are escaped because they are ours and not the caller's. Somebody
     * searching for "50%" otherwise matches every event on the listing, which reads as the
     * filter being broken rather than as a character having meant something.
     *
     * <p>Nothing is folded here. The query does {@code lower(unaccent(...))} to both the title
     * and this pattern, which is the only way the two are guaranteed to agree - Java's
     * normalizer strips combining marks and would leave Đ alone, so "dem" would find "Đêm" in
     * Postgres and not in a unit test, or the reverse. One folding, in one place.
     */
    /**
     * The search term, or null for "everything".
     *
     * <p>It used to return a SQL LIKE pattern - {@code %term%} with {@code \}, {@code %} and
     * {@code _} escaped - and an absent search was {@code %}, which matched everything and so
     * needed no branch. Neither idea survives: LIKE has no meaning here, and criteria built in
     * Java omit a clause rather than widening it. The escaping moved too, to
     * {@code Pattern.quote} at the point the regex is built, which is the right place for it -
     * the wildcards in what a visitor typed are the pattern's syntax and not theirs, and a
     * search for "50%" that returned the whole listing reads as a broken filter.
     */
    private static String titlePattern(String query) {
        return query == null || query.isBlank() ? null : query.strip();
    }
}
