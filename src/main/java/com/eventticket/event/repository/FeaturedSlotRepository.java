package com.eventticket.event.repository;

import com.eventticket.event.domain.FeaturedSlot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * No policy narrows these, because a slot belongs to the platform and there is no tenant to
 * filter by - see V15. What keeps the writes to an administrator is the use case.
 */
public interface FeaturedSlotRepository extends JpaRepository<FeaturedSlot, UUID> {

    /** The whole row, past, present and scheduled, which is what an administrator has to see. */
    public List<FeaturedSlot> findAllByOrderByStartsAtAscPositionAsc();

    /** Only the placements showing right now, in the order they were placed in. */
    @Query("""
           select s from FeaturedSlot s
           where s.startsAt <= :at and s.endsAt > :at
           order by s.position asc, s.startsAt asc
           """)
    public List<FeaturedSlot> findLiveAt(@Param("at") Instant at);
}
