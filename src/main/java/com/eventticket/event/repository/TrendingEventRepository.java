package com.eventticket.event.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.eventticket.event.domain.Event;

/**
 * The ranked row (KB requirements/009 criterion 15): Events by Tickets sold in a recent window,
 * and by nothing else.
 *
 * <p>Native and over {@code ticket_order} and {@code order_seat}, which belong to
 * {@code checkout} - a module that already depends on this one. Asking the database is the rule
 * here rather than inverting that dependency, exactly as {@code EventRepository.countsFor}
 * does.
 */
public interface TrendingEventRepository extends JpaRepository<Event, UUID> {

    /**
     * The ranked Event ids, highest-selling first, among Events the listing would show anyway.
     *
     * <p><strong>A definer function, not a query.</strong> {@code ticket_order} and
     * {@code order_seat} are tenant-scoped, and a visitor reading the home page is neither the
     * Organization's staff nor the buyer - so the obvious query returns nothing at all from an
     * unauthenticated request. That is the policy working. Widening it so the listing could
     * rank would publish every Order in the system to everybody, to serve one row; V15 grants
     * exactly this read instead, which is the same answer V12 gives for a platform
     * administrator reading owners.
     *
     * <p><strong>Ids and an order, never a count.</strong> criterion 15 publishes the ranking
     * and withholds the figures behind it. Keeping the count out of the function's signature
     * makes that a property of the schema rather than of whoever writes the mapping next.
     */
    @Query(value = "select event_id as eventId, rank as rank "
                 + "from trending_event_ids(:since, :now, :limit)", nativeQuery = true)
    public List<Ranked> findTrending(@Param("since") Instant since,
                                     @Param("now") Instant now,
                                     @Param("limit") int limit);

    /** Projection for {@link #findTrending}. There is no count here, deliberately. */
    public interface Ranked {
        UUID getEventId();

        int getRank();
    }
}
