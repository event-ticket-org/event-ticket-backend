package com.eventticket.event.repository;

import com.eventticket.event.domain.Event;
import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.error.ApiException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Row-level security narrows every read here to the active Organization plus any published
 * Event, which is what lets the public queries below run with no tenant at all.
 *
 * <p>Both listings page by keyset rather than by offset: an offset shifts under inserts, and
 * a buyer scrolling a listing while an organization publishes would see rows twice or not at
 * all. The cursor is the last row's sort key, so the page after it is exact.
 *
 * <p>No parameter here is optional, and none is compared to null. An unfiltered status is the
 * whole set of statuses and an absent bound is the edge of time, because Postgres cannot infer
 * the type of a bare parameter in {@code ? is null} and rejects the statement outright. See
 * {@code PageCursor}.
 */
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("""
           select e from Event e
           where e.organizationId = :organizationId
             and e.status in :statuses
             and (e.createdAt < :cursorAt
                  or (e.createdAt = :cursorAt and e.id < :cursorId))
           order by e.createdAt desc, e.id desc
           """)
    public List<Event> findPage(@Param("organizationId") UUID organizationId,
                                @Param("statuses") Collection<Event.Status> statuses,
                                @Param("cursorAt") Instant cursorAt,
                                @Param("cursorId") UUID cursorId,
                                Pageable page);

    /**
     * requirements/009: published, listed, not yet started, and belonging to an Organization
     * that is approved now - not merely one that was approved when it published.
     */
    @Query("""
           select e from Event e
           where e.publishedAt is not null
             and e.listed = true
             and e.startsAt > :now
             and e.startsAt >= :startsAfter
             and e.startsAt <= :startsBefore
             and e.organizationId in (select o.id from Organization o where o.status = :approved)
             and (e.startsAt > :cursorAt
                  or (e.startsAt = :cursorAt and e.id > :cursorId))
           order by e.startsAt asc, e.id asc
           """)
    public List<Event> findPublicPage(@Param("now") Instant now,
                                      @Param("approved") Organization.Status approved,
                                      @Param("startsAfter") Instant startsAfter,
                                      @Param("startsBefore") Instant startsBefore,
                                      @Param("cursorAt") Instant cursorAt,
                                      @Param("cursorId") UUID cursorId,
                                      Pageable page);

    /** The same listing narrowed to a city, which is a property of the Venue rather than the Event. */
    @Query("""
           select e from Event e
           where e.publishedAt is not null
             and e.listed = true
             and e.startsAt > :now
             and e.startsAt >= :startsAfter
             and e.startsAt <= :startsBefore
             and e.venueId in :venueIds
             and e.organizationId in (select o.id from Organization o where o.status = :approved)
             and (e.startsAt > :cursorAt
                  or (e.startsAt = :cursorAt and e.id > :cursorId))
           order by e.startsAt asc, e.id asc
           """)
    public List<Event> findPublicPageAtVenues(@Param("now") Instant now,
                                              @Param("approved") Organization.Status approved,
                                              @Param("venueIds") List<UUID> venueIds,
                                              @Param("startsAfter") Instant startsAfter,
                                              @Param("startsBefore") Instant startsBefore,
                                              @Param("cursorAt") Instant cursorAt,
                                              @Param("cursorId") UUID cursorId,
                                              Pageable page);

    public default Event findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Event"));
    }
}
