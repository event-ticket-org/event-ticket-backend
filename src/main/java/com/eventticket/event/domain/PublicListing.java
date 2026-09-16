package com.eventticket.event.domain;

import com.eventticket.shared.page.Paged;
import java.util.List;

/**
 * A page of the public listing, and the facet counts beside it (KB requirements/009
 * criterion 17).
 *
 * <p>The counts are not part of {@link Paged} because they are not part of a page: they are
 * counted under the filters already applied <em>minus</em> the Category filter, which is the
 * number a visitor is choosing between rather than the number of the page they are on. And
 * they are the same on every page of a listing, so they are computed for the first and
 * omitted after it - recomputing them as a cursor advances is work nobody reads.
 *
 * @param facets every Category, including those matching nothing. A Category shown greyed with
 *               a zero beside it tells a visitor their other filters emptied it; one silently
 *               missing reads as one that does not exist.
 */
public record PublicListing(Paged<PublicEventView> page, List<CategoryCount> facets) {

    /** A Category and how many Events it would return. */
    public record CategoryCount(String slug, String name, long count) {}
}
