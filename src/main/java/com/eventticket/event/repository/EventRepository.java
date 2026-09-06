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
 * whole set of statuses, an absent bound is the edge of time, and an absent text filter is
 * {@code %} - which matches everything and so needs no branch. Postgres cannot infer the type
 * of a bare parameter in {@code ? is null} and rejects the statement outright, and a query
 * built in two shapes is two queries to keep correct. See {@code PageCursor}.
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
     *
     * <p>On {@code status}, not on {@code publishedAt}. "Has ever been published" is a
     * different question and a one-way door: closing sales and cancelling both leave the
     * timestamp exactly where it was, so an event nobody can buy a ticket for would sit in the
     * listing until it started (criterion 10). The public *page* asks the other question
     * deliberately - a link to a cancelled show must still open (criterion 9).
     */
    @Query("""
           select e from Event e
           where e.status = :published
             and e.listed = true
             and e.startsAt > :now
             and e.startsAt >= :startsAfter
             and e.startsAt <= :startsBefore
             and lower(function('unaccent', e.title))
                 like lower(function('unaccent', :title)) escape '\\'
             and e.organizationId in (select o.id from Organization o where o.status = :approved)
             and (e.startsAt > :cursorAt
                  or (e.startsAt = :cursorAt and e.id > :cursorId))
           order by e.startsAt asc, e.id asc
           """)
    public List<Event> findPublicPage(@Param("now") Instant now,
                                      @Param("published") Event.Status published,
                                      @Param("approved") Organization.Status approved,
                                      @Param("startsAfter") Instant startsAfter,
                                      @Param("startsBefore") Instant startsBefore,
                                      @Param("title") String title,
                                      @Param("cursorAt") Instant cursorAt,
                                      @Param("cursorId") UUID cursorId,
                                      Pageable page);

    /** The same listing narrowed to a city, which is a property of the Venue rather than the Event. */
    @Query("""
           select e from Event e
           where e.status = :published
             and e.listed = true
             and e.startsAt > :now
             and e.startsAt >= :startsAfter
             and e.startsAt <= :startsBefore
             and lower(function('unaccent', e.title))
                 like lower(function('unaccent', :title)) escape '\\'
             and e.venueId in :venueIds
             and e.organizationId in (select o.id from Organization o where o.status = :approved)
             and (e.startsAt > :cursorAt
                  or (e.startsAt = :cursorAt and e.id > :cursorId))
           order by e.startsAt asc, e.id asc
           """)
    public List<Event> findPublicPageAtVenues(@Param("now") Instant now,
                                              @Param("published") Event.Status published,
                                              @Param("approved") Organization.Status approved,
                                              @Param("venueIds") List<UUID> venueIds,
                                              @Param("startsAfter") Instant startsAfter,
                                              @Param("startsBefore") Instant startsBefore,
                                              @Param("title") String title,
                                              @Param("cursorAt") Instant cursorAt,
                                              @Param("cursorId") UUID cursorId,
                                              Pageable page);

    /**
     * How many seats each Event has sold, and how many of its Orders are holding money that
     * should be given back (requirements/008 criterion 10).
     *
     * <p>Native, and over {@code ticket_order} and {@code order_seat}, which belong to
     * {@code checkout} - a module that already depends on this one. Asking the database is the
     * rule here rather than inverting that dependency for two numbers, the same way a Venue in
     * use is refused by a trigger instead of by a query into {@code event}.
     *
     * <p>Whole pages at a time, because the counts are shown in a list and a query per row is
     * the slowness that only appears once somebody has real data. Row-level security still
     * applies: this runs as the application role under the caller's tenant.
     *
     * <p>A REFUNDED Order is not counted as sold. Its seats went back on sale, so counting them
     * would tell an organizer they have less capacity left than they do.
     */
    @Query(value = """
           select o.event_id                                          as eventId,
                  count(s.id) filter (where o.status = 'PAID')         as sold,
                  count(distinct o.id) filter (where o.refund_required) as refundRequired
             from ticket_order o
             left join order_seat s on s.order_id = o.id
            where o.event_id in (:eventIds)
            group by o.event_id
           """, nativeQuery = true)
    public List<EventCounts> countsFor(@Param("eventIds") Collection<UUID> eventIds);

    /** Projection for {@link #countsFor}. Spring Data maps the columns by name. */
    public interface EventCounts {
        UUID getEventId();

        long getSold();

        long getRefundRequired();
    }

    public default Event findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Event"));
    }
}
