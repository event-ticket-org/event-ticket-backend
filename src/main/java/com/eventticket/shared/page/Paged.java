package com.eventticket.shared.page;

import java.util.List;

/**
 * One page of results and the cursor that follows it, mirroring the contract's {@code Page}.
 *
 * <p>A null {@code nextCursor} means this is the last page. The contract says the field is
 * "absent or null when there are no further pages", so a client that keeps asking until the
 * cursor disappears terminates.
 */
public record Paged<T>(List<T> items, String nextCursor) {

    public static <T> Paged<T> lastPage(List<T> items) {
        return new Paged<>(items, null);
    }
}
