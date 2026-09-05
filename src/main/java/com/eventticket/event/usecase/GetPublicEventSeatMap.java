package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventSeatMapView;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.shared.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Event Seat Map a buyer picks seats from. Frozen at publish; only availability moves.
 *
 * <p>A Draft has no seats of its own and no public page either, so there is nothing to show
 * here until the Event is published - a manager previewing a draft reads the Venue's map.
 */
@Component
public class GetPublicEventSeatMap {

    private final EventRepository events;
    private final EventSeatRepository seats;

    public GetPublicEventSeatMap(EventRepository events, EventSeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    @Transactional(readOnly = true)
    public EventSeatMapView get(UUID eventId) {
        Event event = events.findOrThrow(eventId);
        if (!event.isPublished()) {
            throw ApiException.notFound("Event");
        }
        return new EventSeatMapView(seats.findByEventIdOrderByLabelAsc(eventId), event.mapElements());
    }
}
