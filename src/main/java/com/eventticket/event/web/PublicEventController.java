package com.eventticket.event.web;

import com.eventticket.api.PublicApi;
import com.eventticket.api.model.EventSeatMap;
import com.eventticket.api.model.PublicEvent;
import com.eventticket.api.model.PublicEventPage;
import com.eventticket.event.usecase.GetPublicEvent;
import com.eventticket.event.usecase.GetPublicEventSeatMap;
import com.eventticket.event.usecase.ListPublicEvents;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unauthenticated half of the API. No tenant reaches these requests, and none is needed:
 * the row-level security policies admit a published Event to everyone, so the same queries
 * that a manager runs return the public rows here and nothing else.
 */
@RestController
public class PublicEventController implements PublicApi {

    private final ListPublicEvents listPublicEvents;
    private final GetPublicEvent getPublicEvent;
    private final GetPublicEventSeatMap getPublicEventSeatMap;

    public PublicEventController(ListPublicEvents listPublicEvents, GetPublicEvent getPublicEvent,
                          GetPublicEventSeatMap getPublicEventSeatMap) {
        this.listPublicEvents = listPublicEvents;
        this.getPublicEvent = getPublicEvent;
        this.getPublicEventSeatMap = getPublicEventSeatMap;
    }

    @Override
    public ResponseEntity<PublicEventPage> publicEventsGet(String city, OffsetDateTime startsAfter,
                                                           OffsetDateTime startsBefore,
                                                           Integer limit, String cursor) {
        var page = listPublicEvents.list(city, at(startsAfter), at(startsBefore), limit, cursor);

        var dto = new PublicEventPage();
        page.items().forEach(view -> dto.addItemsItem(EventMapper.toSummaryDto(view)));
        dto.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<PublicEvent> publicEventsEventIdGet(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toPublicDto(getPublicEvent.get(eventId)));
    }

    @Override
    public ResponseEntity<EventSeatMap> publicEventsEventIdSeatMapGet(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toDto(getPublicEventSeatMap.get(eventId)));
    }

    private static Instant at(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
