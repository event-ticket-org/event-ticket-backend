package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.domain.PublicListing;
import com.eventticket.event.repository.EventCategoryRepository;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.event.repository.SeatCounts;
import com.eventticket.event.support.PageCursor;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
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
 * requirements/009: published, listed Events of approved Organizations that have not started.
 *
 * <p>Ordered by start time, and never by who is asking (criterion 5). Relevance ordering is the
 * other half of that criterion and is refused here rather than silently downgraded - see
 * {@link #requireSupported}.
 *
 * <p>Every filter is expressed as its widest value rather than as a null: an absent city and an
 * absent category are {@code %}, an absent bound is the edge of time, an absent text query
 * matches everything. That is not a style preference - Postgres cannot infer the type of a bare
 * parameter in {@code ? is null} and rejects the statement outright, and a query written in two
 * shapes is two queries to keep correct.
 */
@Component
public class ListPublicEvents {

    /** Matches every row, which is what "this filter was not used" has to mean in one query shape. */
    private static final String ANY = "%";

    private final EventRepository events;
    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final OrganizationRepository organizations;
    private final EventCategoryRepository categories;

    public ListPublicEvents(EventRepository events, EventSeatRepository seats,
                     PricingTierRepository tiers, VenueRepository venues,
                     OrganizationRepository organizations, EventCategoryRepository categories) {
        this.events = events;
        this.seats = seats;
        this.tiers = tiers;
        this.venues = venues;
        this.organizations = organizations;
        this.categories = categories;
    }

    /**
     * @param relevanceOrdered criterion 5's second ordering. Refused while nothing can compute
     *                         it, because a client that asked for relevance and quietly got
     *                         chronological has no way to tell.
     */
    @Transactional(readOnly = true)
    public PublicListing list(String query, boolean relevanceOrdered, String categorySlug,
                              String citySlug, Instant startsAfter, Instant startsBefore,
                              int limit, String cursor) {
        requireSupported(relevanceOrdered, query);

        PageCursor from = PageCursor.decode(cursor, PageCursor.FIRST_ASCENDING);
        PageRequest page = PageRequest.ofSize(limit + 1);
        Instant now = Instant.now();
        Instant after = PageCursor.orBeginning(startsAfter);
        Instant before = PageCursor.orEndOfTime(startsBefore);
        String pattern = searchPattern(query);
        String category = orAny(categorySlug);
        String city = orAny(citySlug);

        List<Event> found = events.findPublicPage(now, Event.Status.PUBLISHED,
                Organization.Status.APPROVED, after, before, category, city, pattern,
                from.at(), from.id(), page);

        // Only the first page carries facets. They are the same for every page of a listing,
        // and a cursor that recomputed them would pay for six counts nobody reads again.
        List<PublicListing.CategoryCount> facets = cursor == null
                ? facets(now, after, before, city, pattern)
                : List.of();

        boolean more = found.size() > limit;
        List<Event> visible = more ? found.subList(0, limit) : found;
        if (visible.isEmpty()) {
            return new PublicListing(Paged.lastPage(List.of()), facets);
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
                    venue.name(), venue.city().name(), venue.city().slug(), venue.timezone(),
                    EventPricing.of(event, null, tiersByEvent.getOrDefault(event.id(), List.of())),
                    SeatCounts.of(counted, event.id()).available(),
                    SeatCounts.of(counted, event.id()).total());
        }).toList();

        Event last = visible.get(visible.size() - 1);
        return new PublicListing(
                new Paged<>(items, more ? PageCursor.encode(last.startsAt(), last.id()) : null),
                facets);
    }

    /**
     * Criterion 5's two orderings, one of which nothing here can compute yet.
     *
     * <p>Refusing is the honest answer while that is true. Relevance needs a score, and this
     * query has none: ordering by how well a title matched would mean ranking in SQL over a
     * LIKE that has no notion of "how well". Answering chronologically to a client that asked
     * for relevance would satisfy the request and lose the information that it was not honoured
     * - which is exactly what criterion 5 asks a client not to have to infer.
     */
    private static void requireSupported(boolean relevanceOrdered, String query) {
        if (!relevanceOrdered) {
            return;
        }
        if (query == null || query.isBlank()) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "Sorting by relevance needs something to be relevant to. Send a search term, "
                            + "or leave the ordering to start time.",
                    Map.of("field", "sort"));
        }
        throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                "Sorting by relevance is not available yet. Results are ordered by start time.",
                Map.of("field", "sort"));
    }

    /**
     * Every Category with its count, including the ones matching nothing.
     *
     * <p>The query answers only the Categories that matched, because a GROUP BY has no rows to
     * group for the others. Criterion 17 wants the zeroes sent: a Category shown greyed with a
     * zero beside it tells a visitor that their other filters emptied it, where one silently
     * missing from the list reads as one that does not exist.
     */
    private List<PublicListing.CategoryCount> facets(Instant now, Instant after, Instant before,
                                                     String city, String pattern) {
        Map<String, Long> counted = events.countPublicByCategory(now, Event.Status.PUBLISHED,
                        Organization.Status.APPROVED, after, before, city, pattern)
                .stream()
                .collect(Collectors.toMap(EventRepository.CategoryCount::getSlug,
                        EventRepository.CategoryCount::getCount));

        return categories.findAllByOrderByPositionAsc().stream()
                .map(category -> new PublicListing.CategoryCount(category.slug(), category.name(),
                        counted.getOrDefault(category.slug(), 0L)))
                .toList();
    }

    private static String orAny(String filter) {
        return filter == null || filter.isBlank() ? ANY : filter;
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
     * <p>Nothing is folded here. The query does {@code lower(immutable_unaccent(...))} to both
     * the column and this pattern, which is the only way the two are guaranteed to agree -
     * Java's normalizer strips combining marks and would leave Đ alone, so "dem" would find
     * "Đêm" in Postgres and not in a unit test, or the reverse. One folding, in one place.
     */
    private static String searchPattern(String query) {
        if (query == null || query.isBlank()) {
            return ANY;
        }
        String escaped = query.strip()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
