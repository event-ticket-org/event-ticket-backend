package com.eventticket.event.repository;

import com.eventticket.event.domain.EventSeat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {

    public List<EventSeat> findByEventIdOrderByLabelAsc(UUID eventId);

    public List<EventSeat> findByEventIdAndIdIn(UUID eventId, List<UUID> ids);

    public long countByEventIdAndForSaleTrue(UUID eventId);

    @Query("select distinct s.tierName from EventSeat s where s.eventId = :eventId")
    public List<String> findTierNames(@Param("eventId") UUID eventId);

    /**
     * Seats still on sale, for a page of Events at once.
     *
     * <p>One grouped query rather than a count per Event: the listing returns a page at a time
     * and a count each would put the page size into the number of round trips, which is the
     * shape that looks fine on a laptop with four events.
     *
     * <p>The three conditions are {@link EventSeat#availability()} written as SQL, and they
     * have to stay that way - a listing that disagreed with the seat map it links to would be
     * worse than one that said nothing. A held seat is not available: the hold expires, but
     * somebody told "one left" who then finds none has been misled on a technicality
     * (requirements/009 criterion 3).
     */
    @Query("""
           select new com.eventticket.event.repository.SeatsOnSale(s.eventId, count(s))
           from EventSeat s
           where s.eventId in :eventIds
             and s.forSale = true
             and s.soldAt is null
             and (s.heldUntil is null or s.heldUntil <= :now)
           group by s.eventId
           """)
    public List<SeatsOnSale> countOnSale(@Param("eventIds") List<UUID> eventIds,
                                         @Param("now") java.time.Instant now);
}
