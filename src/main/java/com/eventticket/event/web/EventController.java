package com.eventticket.event.web;

import com.eventticket.api.EventsApi;
import com.eventticket.api.model.CoverConfirmation;
import com.eventticket.api.model.CoverUpload;
import com.eventticket.api.model.EventInput;
import com.eventticket.api.model.EventPage;
import com.eventticket.api.model.EventPatch;
import com.eventticket.api.model.EventStatus;
import com.eventticket.api.model.PricingTier;
import com.eventticket.api.model.PricingTierInput;
import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventChanges;
import com.eventticket.event.usecase.BeginCoverUpload;
import com.eventticket.event.usecase.CloseSales;
import com.eventticket.event.usecase.CreateEvent;
import com.eventticket.event.usecase.GetEvent;
import com.eventticket.event.usecase.ListEvents;
import com.eventticket.event.usecase.PublishEvent;
import com.eventticket.event.usecase.RemoveEventCover;
import com.eventticket.event.usecase.SetEventCover;
import com.eventticket.event.usecase.SetPricingTiers;
import com.eventticket.event.usecase.UpdateEvent;
import com.eventticket.shared.money.Money;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController implements EventsApi {

    private final CreateEvent createEvent;
    private final ListEvents listEvents;
    private final GetEvent getEvent;
    private final UpdateEvent updateEvent;
    private final SetPricingTiers setPricingTiers;
    private final PublishEvent publishEvent;
    private final CloseSales closeSales;
    private final BeginCoverUpload beginCoverUpload;
    private final SetEventCover setEventCover;
    private final RemoveEventCover removeEventCover;

    public EventController(CreateEvent createEvent, ListEvents listEvents, GetEvent getEvent,
                    UpdateEvent updateEvent, SetPricingTiers setPricingTiers,
                    PublishEvent publishEvent, CloseSales closeSales,
                    BeginCoverUpload beginCoverUpload, SetEventCover setEventCover,
                    RemoveEventCover removeEventCover) {
        this.createEvent = createEvent;
        this.listEvents = listEvents;
        this.getEvent = getEvent;
        this.updateEvent = updateEvent;
        this.setPricingTiers = setPricingTiers;
        this.publishEvent = publishEvent;
        this.closeSales = closeSales;
        this.beginCoverUpload = beginCoverUpload;
        this.setEventCover = setEventCover;
        this.removeEventCover = removeEventCover;
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Event> eventsPost(EventInput request) {
        var created = createEvent.create(request.getTitle(), request.getDescription(),
                request.getVenueId(), request.getStartsAt().toInstant(),
                at(request.getDoorsOpenAt()), at(request.getEndsAt()),
                request.getListed() == null || request.getListed());
        return ResponseEntity.status(HttpStatus.CREATED).body(EventMapper.toDto(created));
    }

    @Override
    public ResponseEntity<EventPage> eventsGet(EventStatus status, Integer limit, String cursor) {
        var page = listEvents.list(
                status == null ? null : Event.Status.valueOf(status.getValue()), limit, cursor);

        var dto = new EventPage();
        page.items().forEach(detail -> dto.addItemsItem(EventMapper.toDto(detail)));
        dto.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Event> eventsEventIdGet(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toDto(getEvent.get(eventId)));
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Event> eventsEventIdPatch(
            UUID eventId, EventPatch request) {
        return ResponseEntity.ok(EventMapper.toDto(updateEvent.update(eventId, toChanges(request))));
    }

    /**
     * Takes {@code PricingTierInput}, not {@code PricingTier}. The contract split the two so a
     * response can report a tier that is named and not yet priced, while a request cannot ask
     * for that - unpricing a tier is not an operation this API has.
     */
    @Override
    public ResponseEntity<List<PricingTier>> eventsEventIdPricingTiersPut(
            UUID eventId, List<PricingTierInput> request) {
        Map<String, Money> prices = new LinkedHashMap<>();
        request.forEach(tier -> prices.put(tier.getName(), EventMapper.toMoney(tier.getPrice())));
        return ResponseEntity.ok(EventMapper.toDto(setPricingTiers.set(eventId, prices)));
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Event> eventsEventIdPublishPost(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toDto(publishEvent.publish(eventId)));
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Event> eventsEventIdCloseSalesPost(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toDto(closeSales.close(eventId)));
    }

    @Override
    public ResponseEntity<CoverUpload> eventsEventIdCoverUploadsPost(UUID eventId) {
        var authorised = beginCoverUpload.begin(eventId);
        var form = authorised.form();
        var dto = new CoverUpload(authorised.uploadId(), URI.create(form.url()),
                form.fields(), form.fileField(),
                form.expiresAt().atOffset(ZoneOffset.UTC));
        dto.setMaxBytes(form.maxBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Event> eventsEventIdCoverPut(
            UUID eventId, CoverConfirmation request) {
        return ResponseEntity.ok(EventMapper.toDto(
                setEventCover.set(eventId, request.getUploadId(), request.getAlt())));
    }

    @Override
    public ResponseEntity<Void> eventsEventIdCoverDelete(UUID eventId) {
        removeEventCover.remove(eventId);
        return ResponseEntity.noContent().build();
    }

    private static EventChanges toChanges(EventPatch request) {
        return new EventChanges(
                request.getTitle(),
                request.getDescription(),
                request.getCoverImageAlt(),
                at(request.getStartsAt()),
                at(request.getDoorsOpenAt()),
                at(request.getEndsAt()),
                request.getListed(),
                request.getUnsellableSeatIds());
    }

    private static Instant at(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
