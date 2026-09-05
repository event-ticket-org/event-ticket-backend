package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.EventSeat;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.MapSeat;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criteria 5-7, and the moment KB invariant 12 starts applying: from here
 * on, nothing a sold Ticket depends on may change under it.
 *
 * <p>Publishing copies the Venue's Seat Map into rows of its own (invariant 8). The copy is
 * the freeze: the Venue's map stays editable for the next Event held there, and this Event
 * stops reading it. Nothing has to be locked, and nobody has to remember not to touch it.
 *
 * <p>The four preconditions are checked separately and refused separately, because "cannot
 * publish" is not an answer a manager can act on - which tier is unpriced, or that the start
 * time has passed, is.
 */
@Component
public class PublishEvent {

    private static final Logger log = LoggerFactory.getLogger(PublishEvent.class);

    private final EventRepository events;
    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final OrganizationRepository organizations;
    private final Managers managers;
    private final AuditTrail audit;

    public PublishEvent(EventRepository events, EventSeatRepository seats, PricingTierRepository tiers,
                 VenueRepository venues, OrganizationRepository organizations,
                 Managers managers, AuditTrail audit) {
        this.events = events;
        this.seats = seats;
        this.tiers = tiers;
        this.venues = venues;
        this.organizations = organizations;
        this.managers = managers;
        this.audit = audit;
    }

    @Transactional
    public EventDetail publish(UUID eventId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        if (!event.isDraft()) {
            log.warn("Publish refused: eventId={} is already {}", eventId, event.status());
        }
        event.requireDraft();

        // Criterion 5, first clause. The message explains the wait rather than stating it.
        organizations.findOrThrow(organizationId).requireApproved();

        SeatMapDocument map = venues.findOrThrow(event.venueId()).seatMap();
        requireSellableSeats(eventId, map);
        requireFutureStart(event);

        EventPricing pricing = EventPricing.of(map.tierNames(), tiers.findByEventId(eventId));
        requireEveryTierPriced(eventId, pricing);

        // A tier row can outlive the last seat that used it, if the map was edited after the
        // price was set. Publishing is where the two are reconciled for good.
        tiers.deleteAll(tiers.findByEventId(eventId).stream()
                .filter(row -> !map.tierNames().contains(row.name()))
                .toList());

        // Order matters. The event_seat_frozen trigger refuses an insert into a published
        // Event, and inside this transaction it can already see an update that has been
        // flushed - so the seats go in while the Event is still a Draft, and only then does
        // it become published.
        seats.saveAll(map.seats().stream().map(seat -> asEventSeat(organizationId, eventId, seat)).toList());
        seats.flush();

        event.freezeSeatMapElements(map.elements());
        event.publish(Instant.now());
        events.saveAndFlush(event);

        audit.record(organizationId, AuditTrail.EVENT_PUBLISHED, event.title());
        log.info("Published event eventId={} seats={} tiers={}",
                eventId, map.seats().size(), pricing.tiers().size());

        return EventDetail.of(event, pricing);
    }

    private static EventSeat asEventSeat(UUID organizationId, UUID eventId, MapSeat seat) {
        return new EventSeat(organizationId, eventId, seat.label(), seat.x(), seat.y(), seat.tierName());
    }

    /** Criterion 5: at least one sellable seat. An event with nothing to sell is a mistake. */
    private static void requireSellableSeats(UUID eventId, SeatMapDocument map) {
        if (!map.hasSeats()) {
            log.warn("Publish refused: eventId={} has no seats", eventId);
            throw new ApiException(ErrorCodes.PUBLISH_PRECONDITION_FAILED,
                    "This event's venue has no seats yet. Draw the seat map before publishing.");
        }
    }

    /** Criterion 5: a start time in the future. */
    private static void requireFutureStart(Event event) {
        if (!event.startsAt().isAfter(Instant.now())) {
            log.warn("Publish refused: eventId={} starts in the past", event.id());
            throw new ApiException(ErrorCodes.PUBLISH_PRECONDITION_FAILED,
                    "This event starts in the past. Set a future start time before publishing.");
        }
    }

    /** Criterion 3: a price for every tier in use, named so the manager knows which. */
    private static void requireEveryTierPriced(UUID eventId, EventPricing pricing) {
        List<String> unpriced = pricing.unpricedNames();
        if (!unpriced.isEmpty()) {
            log.warn("Publish refused: eventId={} has unpriced tiers {}", eventId, unpriced);
            throw new ApiException(ErrorCodes.PUBLISH_PRECONDITION_FAILED,
                    "These pricing tiers have no price yet: " + String.join(", ", unpriced) + ".",
                    Map.of("unpricedTiers", Set.copyOf(unpriced)));
        }
    }
}
