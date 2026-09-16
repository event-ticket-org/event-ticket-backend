package com.eventticket.event.repository;

import com.eventticket.event.domain.Event;
import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.money.Money;
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
     *
     * <p><strong>One query where there were two.</strong> The city used to be applied by
     * looking up venue ids first and passing an IN list, because a city is a property of the
     * Venue. It is now an {@code exists} with the same widest-value idiom as every other filter
     * here - an absent city is {@code %} - which removes the second query shape and the empty
     * IN list that had to be special-cased around it.
     *
     * <p><strong>The text filter spans four fields</strong> (criterion 18), and only the first
     * of them is indexed. {@code event_title_trgm_idx} covers the title; a description, a Venue
     * name and an Organization name each matched by a leading wildcard are scans. Indexing all
     * four is four GIN indexes on a table that is written far more often than this listing is
     * read, and the benchmark is what should decide that rather than a guess made here.
     *
     * <p>{@code immutable_unaccent}, not {@code unaccent}: they compute the same answer, and
     * only the first one can be an index expression. Calling the STABLE form here would leave
     * the index V14 created unused and nothing would say so.
     */
    @Query("""
           select e from Event e
           where e.status = :published
             and e.listed = true
             and e.startsAt > :now
             and e.startsAt >= :startsAfter
             and e.startsAt <= :startsBefore
             and e.category.slug like :categorySlug
             and exists (select 1 from Venue v where v.id = e.venueId
                           and v.city.slug like :citySlug)
             and (lower(function('immutable_unaccent', e.title))
                      like lower(function('immutable_unaccent', :q)) escape '\\'
                  or lower(function('immutable_unaccent', e.description))
                      like lower(function('immutable_unaccent', :q)) escape '\\'
                  or exists (select 1 from Venue vq where vq.id = e.venueId
                               and lower(function('immutable_unaccent', vq.name))
                                   like lower(function('immutable_unaccent', :q)) escape '\\')
                  or exists (select 1 from Organization oq where oq.id = e.organizationId
                               and lower(function('immutable_unaccent', oq.name))
                                   like lower(function('immutable_unaccent', :q)) escape '\\'))
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
                                      @Param("categorySlug") String categorySlug,
                                      @Param("citySlug") String citySlug,
                                      @Param("q") String q,
                                      @Param("cursorAt") Instant cursorAt,
                                      @Param("cursorId") UUID cursorId,
                                      Pageable page);

    /**
     * How many Events each Category would return under the filters already applied, with the
     * Category filter itself left out (criterion 17).
     *
     * <p>Leaving it out is the whole point and is easy to get wrong: counted *with* it, five of
     * the six numbers are zero and the sixth is the size of the page the visitor is already
     * looking at. The counts are for the choice, not for the current state.
     *
     * <p>The cursor predicate is absent too. A facet count is a property of the listing and not
     * of a page of it - counting from the cursor onwards would make the numbers shrink as
     * somebody scrolled.
     *
     * <p>Categories matching nothing are missing from this result rather than present with a
     * zero, because a GROUP BY has no rows to group. {@code ListPublicEvents} fills them in
     * from the Category set, which is where the zero criterion 17 asks for comes from.
     */
    @Query("""
           select e.category.slug as slug, e.category.name as name, count(e) as count
             from Event e
            where e.status = :published
              and e.listed = true
              and e.startsAt > :now
              and e.startsAt >= :startsAfter
              and e.startsAt <= :startsBefore
              and exists (select 1 from Venue v where v.id = e.venueId
                            and v.city.slug like :citySlug)
              and (lower(function('immutable_unaccent', e.title))
                       like lower(function('immutable_unaccent', :q)) escape '\\'
                   or lower(function('immutable_unaccent', e.description))
                       like lower(function('immutable_unaccent', :q)) escape '\\'
                   or exists (select 1 from Venue vq where vq.id = e.venueId
                                and lower(function('immutable_unaccent', vq.name))
                                    like lower(function('immutable_unaccent', :q)) escape '\\')
                   or exists (select 1 from Organization oq where oq.id = e.organizationId
                                and lower(function('immutable_unaccent', oq.name))
                                    like lower(function('immutable_unaccent', :q)) escape '\\'))
              and e.organizationId in (select o.id from Organization o where o.status = :approved)
            group by e.category.slug, e.category.name
           """)
    public List<CategoryCount> countPublicByCategory(@Param("now") Instant now,
                                                     @Param("published") Event.Status published,
                                                     @Param("approved") Organization.Status approved,
                                                     @Param("startsAfter") Instant startsAfter,
                                                     @Param("startsBefore") Instant startsBefore,
                                                     @Param("citySlug") String citySlug,
                                                     @Param("q") String q);

    /** Projection for {@link #countPublicByCategory}. Spring Data maps by alias. */
    public interface CategoryCount {
        String getSlug();

        String getName();

        long getCount();
    }

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
     *
     * <p>The money rides along in the same aggregate rather than in a second query, because it
     * is the same grouping over the same rows and is read on the same screens
     * (requirements/003 criterion 23). It filters on PAID for the reason the sold count does:
     * a refunded Order has given the money back, and a total that still counted it would tell
     * an organizer they hold funds they do not.
     */
    @Query(value = """
           with per_order as (
               select o.event_id, o.id, o.status, o.refund_required,
                      o.total_amount, o.currency,
                      count(s.id) as seats
                 from ticket_order o
                 left join order_seat s on s.order_id = o.id
                where o.event_id in (:eventIds)
                group by o.event_id, o.id, o.status, o.refund_required,
                         o.total_amount, o.currency
           )
           select event_id                                                  as eventId,
                  coalesce(sum(seats) filter (where status = 'PAID'), 0)     as sold,
                  count(*) filter (where refund_required)                    as refundRequired,
                  coalesce(sum(total_amount) filter (where status = 'PAID'), 0)
                                                                            as salesTotal,
                  max(currency) filter (where status = 'PAID')               as salesCurrency
             from per_order
            group by event_id
           """, nativeQuery = true)
    public List<EventCounts> countsFor(@Param("eventIds") Collection<UUID> eventIds);

    /** Projection for {@link #countsFor}. Spring Data maps the columns by name. */
    public interface EventCounts {
        UUID getEventId();

        long getSold();

        long getRefundRequired();

        long getSalesTotal();

        /** Null when the Event has sold nothing, because there is then no Order to take it from. */
        String getSalesCurrency();

        /**
         * The money as a {@link Money}, with the null currency handled once here rather than
         * at each of the four call sites. An Event that has sold nothing has taken zero, and
         * zero of no particular currency is still zero dong in a system whose contract closes
         * the enum at one value.
         */
        public default Money salesTotal() {
            return new Money(getSalesTotal(), getSalesCurrency() == null
                    ? Money.Currency.VND
                    : Money.Currency.valueOf(getSalesCurrency()));
        }
    }

    public default Event findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Event"));
    }
}
