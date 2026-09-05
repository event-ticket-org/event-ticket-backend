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
}
