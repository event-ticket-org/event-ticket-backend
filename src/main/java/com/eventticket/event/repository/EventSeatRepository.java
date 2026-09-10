package com.eventticket.event.repository;

import com.eventticket.event.domain.EventSeat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatRepository extends MongoRepository<EventSeat, UUID> {

    public List<EventSeat> findByEventIdOrderByLabelAsc(UUID eventId);

    public List<EventSeat> findByEventIdAndIdIn(UUID eventId, List<UUID> ids);

    public long countByEventIdAndForSaleTrue(UUID eventId);

    @Query("select distinct s.tierName from EventSeat s where s.eventId = :eventId")
    public List<String> findTierNames(@Param("eventId") UUID eventId);

    /**
     * How many seats are on sale and how many are still free, for a page of Events at once.
     *
     * <p>One grouped query rather than a count per Event: the listing returns a page at a time
     * and a count each would put the page size into the number of round trips, which is the
     * shape that looks fine on a laptop with four events. Both numbers come from the one pass
     * for the same reason.
     *
     * <p>{@code forSale} is the filter and availability is the sum, which is exactly the
     * difference between the two figures: the total is what an organizer put on sale, so a
     * seat they withheld was never available to anybody and counting it would make every event
     * look emptier than it is.
     *
     * <p>The conditions inside the sum are {@link EventSeat#availability()} written as SQL and
     * have to stay that way - a listing that disagreed with the seat map it links to would be
     * worse than one that said nothing. A held seat is not available: the hold expires, but
     * somebody told "one left" who then finds none has been misled on a technicality.
     */
    @Query("""
           select new com.eventticket.event.repository.SeatCounts(
                    s.eventId,
                    sum(case when s.soldAt is null
                                  and (s.heldUntil is null or s.heldUntil <= :now)
                             then 1 else 0 end),
                    count(s))
           from EventSeat s
           where s.eventId in :eventIds
             and s.forSale = true
           group by s.eventId
           """)
    public List<SeatCounts> countSeats(@Param("eventIds") List<UUID> eventIds,
                                       @Param("now") java.time.Instant now);
}
