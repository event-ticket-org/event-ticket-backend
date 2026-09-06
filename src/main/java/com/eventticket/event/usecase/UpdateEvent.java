package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventChanges;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.EventSeat;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criteria 4, 8, 9, 11 and 14.
 *
 * <p>Every field here is one a published Event may still change. The ones it may not - the
 * Venue and the seat map - are not in the contract's {@code EventPatch} at all, and the
 * database refuses them anyway.
 *
 * <p>Moving the start time is the change with a cost attached: criterion 9 says every ticket
 * holder is emailed, and the response says how many. Until Tickets exist (requirements/006)
 * there is nobody to email, and the count returned is a real zero rather than a placeholder -
 * but the notification itself is not yet written, and this is where it goes.
 */
@Component
public class UpdateEvent {

    private static final Logger log = LoggerFactory.getLogger(UpdateEvent.class);

    private final EventRepository events;
    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;
    private final AuditTrail audit;

    public UpdateEvent(EventRepository events, EventSeatRepository seats, PricingTierRepository tiers,
                VenueRepository venues, Managers managers, AuditTrail audit) {
        this.events = events;
        this.seats = seats;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
        this.audit = audit;
    }

    @Transactional
    public EventDetail update(UUID eventId, EventChanges changes) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);

        if (changes.title() != null || changes.description() != null || changes.coverImageUrl() != null) {
            event.describeAs(
                    changes.title() != null ? changes.title() : event.title(),
                    changes.description() != null ? changes.description() : event.description(),
                    changes.coverImageUrl() != null ? changes.coverImageUrl() : event.coverImageUrl());
        }

        Integer notified = reschedule(event, organizationId, changes);

        if (changes.listed() != null && changes.listed() != event.isListed()) {
            event.listPublicly(changes.listed());
            audit.record(organizationId, AuditTrail.EVENT_LISTING_CHANGED, event.title());
            log.info("Changed listing eventId={} listed={}", eventId, changes.listed());
        }

        if (changes.touchesSeats()) {
            withholdFromSale(event, changes.unsellableSeatIds());
        }

        events.save(event);

        SeatMapDocument map = event.isPublished() ? null : venues.findOrThrow(event.venueId()).seatMap();
        EventPricing pricing = EventPricing.of(event, map, tiers.findByEventId(eventId));
        EventDetail detail = EventDetail.of(event, pricing).notifying(notified);
        return events.countsFor(java.util.List.of(eventId)).stream().findFirst()
                .map(counts -> detail.withCounts(counts.getSold(), counts.getRefundRequired()))
                .orElse(detail);
    }

    /**
     * Criteria 9 and 16. The start time and the admission window move in one step, because a
     * reschedule that moved them separately would be refused halfway through - see
     * {@code Event.reschedule}.
     *
     * <p>The audit entry is written whether or not anyone had to be told, because the question
     * it answers later is "who moved this event", not "who was emailed".
     *
     * @return how many ticket holders were notified, or null if nothing moved
     */
    private Integer reschedule(Event event, UUID organizationId, EventChanges changes) {
        boolean startMoved = changes.startsAt() != null && !changes.startsAt().equals(event.startsAt());
        boolean windowMoved = changes.doorsOpenAt() != null || changes.endsAt() != null;
        if (!startMoved && !windowMoved) {
            return null;
        }

        event.reschedule(
                changes.startsAt() != null ? changes.startsAt() : event.startsAt(),
                changes.doorsOpenAt() != null ? changes.doorsOpenAt() : event.doorsOpenAt(),
                changes.endsAt() != null ? changes.endsAt() : event.endsAt());

        if (windowMoved) {
            audit.record(organizationId, AuditTrail.EVENT_ADMISSION_WINDOW_CHANGED, event.title());
            log.info("Changed admission window eventId={} doorsOpenAt={} endsAt={}",
                    event.id(), event.doorsOpenAt(), event.endsAt());
        }
        if (!startMoved) {
            return null;
        }

        audit.record(organizationId, AuditTrail.EVENT_START_TIME_CHANGED, event.title());

        // No Tickets are notified yet. The count is a real zero rather than unimplemented, and
        // this is where the email goes when requirements/006's holders exist to be told.
        int ticketHolders = 0;
        log.info("Moved event eventId={} startsAt={} notified={}",
                event.id(), event.startsAt(), ticketHolders);
        return ticketHolders;
    }

    /**
     * Criteria 4 and 11. Seats exist only after publish, which is also the only time this is
     * useful: withholding a seat before publish is done by not drawing it.
     */
    private void withholdFromSale(Event event, List<UUID> seatIds) {
        if (!event.isPublished()) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "Seats can be held back from sale once the event is published. Until then, "
                            + "the event shows the venue's seat map and has no seats of its own.");
        }

        List<EventSeat> found = seats.findByEventIdAndIdIn(event.id(), seatIds);
        if (found.size() != seatIds.size()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "Some of those seats are not in this event.");
        }
        found.forEach(seat -> seat.offerForSale(false));
        seats.saveAll(found);

        log.info("Withheld seats from sale eventId={} seats={}", event.id(), found.size());
    }
}
