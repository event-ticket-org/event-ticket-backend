package com.eventticket.event.search;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The port the rest of the application sees, so that nothing outside this package knows what a
 * search cluster is.
 *
 * <p>Same shape as {@code ObjectStore}: an interface the feature depends on, and one adapter
 * that speaks the protocol. The reason is the same too - the adapter is the part that cannot be
 * unit tested without the real thing, and everything above it can.
 *
 * <p>It is also what makes the fallback possible. requirements/009 criterion 20 says whatever
 * serves the listing is derived and never the record, and its loss may degrade ordering but
 * must never take the listing down; a listing that called an Elasticsearch client directly
 * would have no seam to fall back at.
 *
 * <p>Absent when nothing is configured. There is deliberately no no-op implementation: a port
 * with a silent stand-in is how {@code LoggingEmailTransport} came to be the only mail
 * transport in every deployment for the life of the project, marking every message SENT. A
 * missing bean is a thing callers can see.
 */
public interface EventSearchIndex {

    /**
     * Creates the index and its alias if they are not there. Safe to call repeatedly.
     *
     * @return true when there was nothing and one was created, which is the caller's signal
     *         that the index has no documents in it and somebody should put some there.
     */
    public boolean ensureReady();

    /** Upserts by document id, so replaying the same document is free. */
    public void index(Collection<EventDocument> documents);

    /** Removes documents whose Events the listing no longer shows. Unknown ids are not an error. */
    public void delete(Collection<UUID> eventIds);

    /**
     * Rebuilds the whole index into a new one and moves the alias onto it, atomically.
     *
     * <p>This is what makes losing the index survivable rather than fatal, and it is also the
     * only way to change a mapping without downtime - which is why it exists before anything
     * needs it.
     */
    public void replaceAll(List<EventDocument> documents);

    /**
     * Which Events match, in the order asked for, with the facet counts beside them.
     *
     * <p>Answers ids rather than documents: the index decides which Events and in what order,
     * and Postgres supplies what a card is drawn from. See {@link SearchQuery} for why that is
     * a change of mind.
     */
    public SearchQuery.Results search(SearchQuery.Criteria criteria);

    /** Whether the cluster answered. Used to decide whether to fall back, never to decide to write. */
    public boolean isAvailable();
}
