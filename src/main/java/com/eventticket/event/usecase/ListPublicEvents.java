package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.domain.PublicListing;
import com.eventticket.event.repository.EventCategoryRepository;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.support.PageCursor;
import com.eventticket.event.search.EventSearchIndex;
import com.eventticket.event.search.SearchCursor;
import com.eventticket.event.search.SearchQuery;
import com.eventticket.event.search.SearchUnavailableException;
import com.eventticket.event.support.PublicEventViews;
import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.page.Paged;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ListPublicEvents.class);

    /** Matches every row, which is what "this filter was not used" has to mean in one query shape. */
    private static final String ANY = "%";

    private final EventRepository events;
    private final EventCategoryRepository categories;
    private final PublicEventViews views;

    /**
     * Absent when nothing is configured, which is a deployment without a search cluster rather
     * than a broken one. The listing serves from Postgres and says nothing about it.
     */
    private final Optional<EventSearchIndex> index;

    public ListPublicEvents(EventRepository events, EventCategoryRepository categories,
                     PublicEventViews views, Optional<EventSearchIndex> index) {
        this.events = events;
        this.categories = categories;
        this.views = views;
        this.index = index;
    }

    /**
     * @param relevanceOrdered criterion 5's second ordering. Needs the index: Postgres has no
     *                         score to sort by, and a LIKE has no notion of how well it matched.
     */
    @Transactional(readOnly = true)
    public PublicListing list(String query, boolean relevanceOrdered, String categorySlug,
                              String citySlug, Instant startsAfter, Instant startsBefore,
                              int limit, String cursor) {
        requireRelevanceHasSomethingToRankBy(relevanceOrdered, query);

        Instant now = Instant.now();
        // Never earlier than now, whatever was asked for: the listing does not show what has
        // already begun, and that is the listing's rule rather than the index's or the query's.
        Instant after = startsAfter == null || startsAfter.isBefore(now) ? now : startsAfter;

        if (index.isPresent()) {
            try {
                return fromIndex(index.get(), query, relevanceOrdered, categorySlug, citySlug,
                        after, startsBefore, limit, cursor, now);
            } catch (SearchUnavailableException e) {
                // criterion 20: losing the index degrades the listing and never takes it down.
                // Logged at WARN with the reason, because a listing quietly serving from the
                // slower path for a week is the kind of thing nobody notices until a bill.
                // The cause, not just this exception's own message. A fallback that says only
                // "the cluster could not answer" is a fallback nobody can debug: the reason is
                // always in what it wrapped, and without it a mapping mistake and a dead
                // cluster produce the same line.
                log.warn("Search unavailable, serving the listing from Postgres: {}",
                        e.getCause() == null ? e.getMessage() : e.getCause().toString(), e);
            }
        }
        return fromPostgres(query, relevanceOrdered, categorySlug, citySlug, after, startsBefore,
                limit, cursor, now);
    }

    /**
     * The index decides which Events and in what order; Postgres supplies the cards.
     *
     * <p>Two reads, and the second one is by primary key for at most a page of ids. That is the
     * whole cost of having one way to build a card rather than two - see {@code SearchQuery}.
     */
    private PublicListing fromIndex(EventSearchIndex searchIndex, String query,
                                    boolean relevanceOrdered, String categorySlug,
                                    String citySlug, Instant after, Instant before, int limit,
                                    String cursor, Instant now) {
        var results = searchIndex.search(new SearchQuery.Criteria(
                blankToNull(query), blankToNull(categorySlug), blankToNull(citySlug),
                after, before, relevanceOrdered, limit, SearchCursor.decode(cursor)));

        // Read back through the listing's own predicate rather than trusting the index. The
        // index is derived and can be a moment behind - an Event cancelled since the last drain
        // is still a document - and a listing that showed one would be a link to a page saying
        // the show is off. The index decides the order; Postgres decides what is true.
        Map<UUID, Event> listable = events
                .findPublicByIds(results.ids(), now, Event.Status.PUBLISHED,
                        Organization.Status.APPROVED)
                .stream().collect(Collectors.toMap(Event::id, event -> event));
        List<Event> ordered = results.ids().stream()
                .map(listable::get).filter(java.util.Objects::nonNull).toList();

        return new PublicListing(
                new Paged<>(views.of(ordered, now),
                        results.next() == null ? null : results.next().encode()),
                namesFor(results.facets()));
    }

    /** Category counts from the index, with the names the index does not carry. */
    private List<PublicListing.CategoryCount> namesFor(List<SearchQuery.CategoryCount> counted) {
        if (counted.isEmpty()) {
            return List.of();
        }
        Map<String, Long> bySlug = counted.stream().collect(
                Collectors.toMap(SearchQuery.CategoryCount::slug,
                        SearchQuery.CategoryCount::count));
        // Every Category, including the ones the index had no bucket for. criterion 17 wants
        // the zeroes: a chip greyed with a zero says the other filters emptied it, where one
        // missing says the category does not exist.
        return categories.findAllByOrderByPositionAsc().stream()
                .map(category -> new PublicListing.CategoryCount(category.slug(), category.name(),
                        bySlug.getOrDefault(category.slug(), 0L)))
                .toList();
    }

    /**
     * The path that works without a search cluster, and the one the benchmark compares against.
     *
     * <p>It cannot rank. requirements/009 criterion 5 says a client asks for an ordering rather
     * than inferring which it got, so a request for relevance is refused here rather than
     * answered chronologically - silently downgrading would satisfy the request and lose the
     * information that it was not honoured. Everything else degrades quietly, which is the
     * right trade in the other direction: a visitor filtering by city does not need to know
     * which system answered.
     */
    private PublicListing fromPostgres(String query, boolean relevanceOrdered,
                                       String categorySlug, String citySlug, Instant after,
                                       Instant before, int limit, String cursor, Instant now) {
        if (relevanceOrdered) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "Sorting by relevance is not available right now. Ask for start time, or "
                            + "try again in a moment.",
                    Map.of("field", "sort"));
        }

        PageCursor from = PageCursor.decode(cursor, PageCursor.FIRST_ASCENDING);
        PageRequest page = PageRequest.ofSize(limit + 1);
        Instant before_ = PageCursor.orEndOfTime(before);
        String pattern = searchPattern(query);
        String category = orAny(categorySlug);
        String city = orAny(citySlug);

        List<Event> found = events.findPublicPage(now, Event.Status.PUBLISHED,
                Organization.Status.APPROVED, after, before_, category, city, pattern,
                from.at(), from.id(), page);

        List<PublicListing.CategoryCount> facets = cursor == null
                ? facets(now, after, before_, city, pattern)
                : List.of();

        boolean more = found.size() > limit;
        List<Event> visible = more ? found.subList(0, limit) : found;
        if (visible.isEmpty()) {
            return new PublicListing(Paged.lastPage(List.of()), facets);
        }

        Event last = visible.get(visible.size() - 1);
        return new PublicListing(
                new Paged<>(views.of(visible, now),
                        more ? PageCursor.encode(last.startsAt(), last.id()) : null),
                facets);
    }

    /**
     * Criterion 5, and the half of it that holds whichever system answers: relevance to nothing
     * is not an ordering. Checked before either path, because it is a property of the request
     * rather than of what is available to serve it.
     */
    private static void requireRelevanceHasSomethingToRankBy(boolean relevanceOrdered,
                                                             String query) {
        if (relevanceOrdered && (query == null || query.isBlank())) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "Sorting by relevance needs something to be relevant to. Send a search term, "
                            + "or leave the ordering to start time.",
                    Map.of("field", "sort"));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
        // The names come from the Category set rather than from the grouped rows: an Event
        // carries its Category's slug and nothing else, and the Categories that matched
        // nothing have no grouped row to carry a name on anyway.

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
