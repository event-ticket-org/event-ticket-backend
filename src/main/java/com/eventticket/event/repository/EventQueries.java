package com.eventticket.event.repository;

import com.eventticket.event.domain.Event;
import com.eventticket.organization.domain.Organization;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The Event reads that a document store cannot express as a single query.
 *
 * <p>{@code findPublicPageAtVenues} is gone: it was the same query as {@code findPublicPage}
 * with one extra {@code in}, duplicated because the two were separate JPQL strings. Composing
 * criteria in Java makes the duplicate unnecessary, so {@code venueIds} is simply nullable -
 * one of the few places building a query in code beats writing it out.
 */
public interface EventQueries {

    public List<Event> findPage(UUID organizationId, Collection<Event.Status> statuses,
                                Instant cursorAt, UUID cursorId, int limit);

    public List<Event> findPublicPage(Instant now, Event.Status published,
                                      Organization.Status approved, List<UUID> venueIds,
                                      Instant startsAfter, Instant startsBefore, String title,
                                      Instant cursorAt, UUID cursorId, int limit);

    public List<EventCounts> countsFor(Collection<UUID> eventIds);
}
