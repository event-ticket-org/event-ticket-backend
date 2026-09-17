package com.eventticket.event.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the listing asks the index for, and what it gets back.
 *
 * <p>Both shapes deliberately stay on ids. The index decides <em>which</em> Events and in
 * <em>what order</em>; Postgres supplies what a card is drawn from.
 *
 * <p>That is a change of mind, and worth saying so. The plan was for the document to carry the
 * card and for Postgres to supply only seat availability - the argument being that
 * {@code ListPublicEvents} already assembled organization and venue names in Java, so the shape
 * matched. Since then {@code PublicEventViews} exists and the curated and ranked rows both use
 * it, so a document-shaped path would be a <em>second</em> way to build a card for a fourth
 * surface. One assembly and an extra read of twenty rows by primary key beats two assemblies
 * that can disagree about what an Event looks like - which is the failure the assembler was
 * extracted to prevent in the first place.
 */
public final class SearchQuery {

    private SearchQuery() {}

    /**
     * @param q             free text, or null for no text filter
     * @param categorySlug  or null
     * @param citySlug      or null
     * @param startsAfter   the earliest start time to include, never null - the listing always
     *                      excludes what has already begun
     * @param byRelevance   requirements/009 criterion 5's second ordering. False means start
     *                      time, which is the default and the only one available without a
     *                      text query.
     * @param after         the previous page's last sort values, or null for the first page
     */
    public record Criteria(String q, String categorySlug, String citySlug,
                           Instant startsAfter, Instant startsBefore,
                           boolean byRelevance, int limit, SearchCursor after) {}

    /**
     * @param ids       matching Event ids, in the order the index answered
     * @param next      the cursor for the page after this one, or null when there is none
     * @param facets    how many Events each Category would return under the same filters with
     *                  the Category filter removed. Empty after the first page, because the
     *                  numbers do not change as a cursor advances.
     */
    public record Results(List<UUID> ids, SearchCursor next, List<CategoryCount> facets) {

        public static Results empty() {
            return new Results(List.of(), null, List.of());
        }
    }

    /** A Category slug and how many Events it holds. The name is filled in by the caller. */
    public record CategoryCount(String slug, long count) {}
}
