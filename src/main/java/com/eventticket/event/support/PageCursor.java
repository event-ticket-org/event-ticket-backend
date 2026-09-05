package com.eventticket.event.support;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The position of the last row on a page, as one opaque string.
 *
 * <p>An instant and an identifier, because an instant alone is not unique - two Events can
 * start at the same second, and a cursor that cannot tell them apart drops one of them. The
 * identifier breaks the tie.
 *
 * <p>Opaque to the client on purpose: it is a sort key, not a page number, and encoding it
 * keeps anyone from constructing one and depending on the ordering it implies.
 *
 * <p>The first page is expressed as a cursor at the edge of time rather than as null.
 * Postgres cannot infer the type of a bare parameter in {@code ? is null}, so a query written
 * with optional cursors fails with "could not determine data type of parameter" - and a
 * sentinel that every row is greater than is simpler than casting every parameter, in the
 * query and in the execution plan both.
 */
public record PageCursor(Instant at, UUID id) {

    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");

    /** Before every row, for a listing ordered ascending. */
    public static final PageCursor FIRST_ASCENDING = new PageCursor(BEGINNING, new UUID(0L, 0L));

    /** After every row, for a listing ordered descending. */
    public static final PageCursor FIRST_DESCENDING = new PageCursor(END_OF_TIME, new UUID(-1L, -1L));

    public static String encode(Instant at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((at.toString() + " " + id).getBytes(StandardCharsets.UTF_8));
    }

    /** A malformed cursor is the client's error, not an empty result. */
    public static PageCursor decode(String cursor, PageCursor firstPage) {
        if (cursor == null || cursor.isBlank()) {
            return firstPage;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(" ", 2);
            return new PageCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "That page cursor is not one this endpoint issued.");
        }
    }

    /** An absent lower bound is the beginning of time, for the same reason. */
    public static Instant orBeginning(Instant bound) {
        return bound == null ? BEGINNING : bound;
    }

    public static Instant orEndOfTime(Instant bound) {
        return bound == null ? END_OF_TIME : bound;
    }
}
