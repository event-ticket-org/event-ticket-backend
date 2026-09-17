package com.eventticket.event.search;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Where a page of search results left off, as Elasticsearch's {@code search_after} values.
 *
 * <p>Keyset, like every other listing here, and for the same reason: an offset shifts under
 * inserts, so a visitor scrolling while an organization publishes sees rows twice or not at
 * all. {@code search_after} is that idea with the index's own sort values instead of a column.
 *
 * <p><strong>It carries whatever the sort was, rather than a fixed pair.</strong> Ordering by
 * start time sorts on {@code [startsAt, id]}; ordering by relevance sorts on
 * {@code [_score, id]}, and a score is a float that nothing in this system stores. A cursor
 * shaped for one of those cannot carry the other, and the alternative - two cursor types and a
 * client that has to know which it is holding - is a distinction the contract does not make.
 *
 * <p>Opaque on purpose. The contract says a client sends back what it was given and nothing
 * more; encoding it means nobody builds one by hand and then finds the sort changed under them.
 */
public record SearchCursor(List<String> sortValues) {

    /**
     * A character none of the sort values can contain. They are epoch milliseconds, a uuid
     * and a relevance score - digits, hex and dots - so a unit separator is unambiguous
     * where a comma or a dash would eventually meet a value containing one.
     *
     * <p>Written as an escape rather than as the byte itself: a raw control character in a
     * source file survives every editor and is visible in none of them.
     */
    private static final String SEPARATOR = "\u001F";

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join(SEPARATOR, sortValues).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return null for an absent or unreadable cursor, which both mean "start at the beginning"
     */
    public static SearchCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            return new SearchCursor(List.of(decoded.split(SEPARATOR, -1)));
        } catch (IllegalArgumentException e) {
            // A cursor somebody typed, or one from a listing whose sort has since changed.
            // The first page is the honest answer to both, and is what an absent cursor means
            // anyway - refusing would turn a stale bookmark into an error page.
            return null;
        }
    }
}
