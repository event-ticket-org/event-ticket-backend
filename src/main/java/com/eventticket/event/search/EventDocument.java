package com.eventticket.event.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An Event as the search index holds it.
 *
 * <p>The rule this shape follows is <strong>index what is slow to search and slow to change;
 * fetch what is fast to look up and fast to change</strong>.
 *
 * <p>So there is no {@code seatsAvailable} here, and its absence is the most important thing
 * about this record. Availability is computed from {@code event_seat} by {@code soldAt} and
 * {@code heldUntil} and moves several times a second during an onsale; anything written into a
 * document is wrong before the write returns. It is fetched for the twenty ids on a page
 * instead - which is what {@code ListPublicEvents} already did in Java before any of this
 * existed.
 *
 * <p>The consequence, written down because it is a real behaviour rather than an oversight:
 * <strong>a sold-out Event cannot be ranked lower than one with seats left.</strong> Nothing
 * here can see that it is sold out. It appears at full relevance with a "Sold out" chip, which
 * is what requirements/009 criterion 21 asks for anyway - ordering that moved with availability
 * would reshuffle a page under a reader mid-scroll.
 *
 * @param priceFrom the cheapest priced tier, in the currency's smallest unit, or null when no
 *                  tier has a price yet. Sortable and filterable; not something a text query
 *                  matches.
 */
public record EventDocument(
        UUID id,
        String title,
        String description,
        String organizationName,
        String venueName,
        String citySlug,
        String categorySlug,
        Instant startsAt,
        Long priceFrom,
        String coverImageUrl,
        String coverImageAlt,
        List<CoverSize> coverImageSizes) {

    /** A rendering of the cover, carried so a card can be drawn without a second read. */
    public record CoverSize(String url, int width) {}
}
