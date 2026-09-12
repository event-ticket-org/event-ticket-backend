package com.eventticket.event.repository;

import com.eventticket.event.domain.EventSeat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventSeatRepository extends MongoRepository<EventSeat, UUID>, EventSeatQueries {

    public List<EventSeat> findByEventIdOrderByLabelAsc(UUID eventId);

    public List<EventSeat> findByEventIdAndIdIn(UUID eventId, List<UUID> ids);

    public long countByEventIdAndForSaleTrue(UUID eventId);

}
