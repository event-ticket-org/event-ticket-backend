package com.eventticket.event.usecase;

import java.util.UUID;

/**
 * Where an Event's cover lives in the store, and where one is still being uploaded to.
 *
 * <p>Keys are built rather than remembered, which is what lets an upload be confirmed without a
 * row to look it up in. It also does the tenancy: the organization and the Event are segments
 * of the key, and both come from a request that has already been checked, so a caller who
 * invents an {@code uploadId} can only ever address a place inside their own Event. There is
 * nothing to guess their way out of.
 *
 * <p>The upload id stays in the served key so that replacing a cover changes its URL. Caches
 * are then correct by construction rather than by a header somebody has to remember to set.
 */
final class CoverImageKeys {

    /** Everything here is expired by a lifecycle rule; see compose.yaml and ADR-0006. */
    private static final String PENDING = "pending";
    private static final String SERVED = "covers";

    private CoverImageKeys() {}

    static String pending(UUID organizationId, UUID eventId, String uploadId) {
        return "%s/%s/%s/%s".formatted(PENDING, organizationId, eventId, uploadId);
    }

    static String served(UUID organizationId, UUID eventId, String uploadId, String extension) {
        return "%s/%s/%s/%s.%s".formatted(SERVED, organizationId, eventId, uploadId, extension);
    }

    /**
     * An upload id is ours, opaque to everyone else, and goes into a path - so it is checked
     * before it is concatenated into one. A caller sending {@code ../} is trying to write
     * somewhere else in the bucket.
     */
    static boolean isWellFormed(String uploadId) {
        return uploadId != null && uploadId.matches("[A-Za-z0-9-]{1,64}");
    }
}
