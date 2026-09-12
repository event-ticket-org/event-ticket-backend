package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.repository.EventCounts;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.event.support.PageCursor;
import com.eventticket.shared.page.Paged;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Organization's own Events, newest first.
 *
 * <p>Tiers and Venues are fetched once for the whole page rather than per Event. A page of
 * twenty drafts would otherwise be forty extra queries, and the seat maps they load are the
 * largest documents in the schema.
 */
@Component
public class ListEvents {

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;

    public ListEvents(EventRepository events, PricingTierRepository tiers, VenueRepository venues,
               Managers managers) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
    }

    @Transactional(readOnly = true)
    public Paged<EventDetail> list(Event.Status status, int limit, String cursor) {
        // KB invariant 3, as in GetEvent: this listing carries prices and sold counts.
        managers.requireCallerCanManageEvents(TenantContext.requireOrganizationId());

        PageCursor from = PageCursor.decode(cursor, PageCursor.FIRST_DESCENDING);

        // No status filter means every status, rather than a null the query has to test for.
        Collection<Event.Status> statuses = status == null
                ? EnumSet.allOf(Event.Status.class)
                : EnumSet.of(status);

        // One more than asked for: if it comes back, there is another page, and that is
        // cheaper than counting the whole table to find out.
        List<Event> page = events.findPage(TenantContext.requireOrganizationId(), statuses,
                from.at(), from.id(), limit + 1);

        boolean more = page.size() > limit;
        List<Event> visible = more ? page.subList(0, limit) : page;
        if (visible.isEmpty()) {
            return Paged.lastPage(List.of());
        }

        Map<UUID, List<PricingTier>> tiersByEvent = tiers
                .findByEventIdIn(visible.stream().map(Event::id).toList())
                .stream().collect(Collectors.groupingBy(PricingTier::eventId));

        Map<UUID, SeatMapDocument> mapsByVenue = venues
                .findByIdIn(visible.stream().filter(e -> !e.isPublished()).map(Event::venueId).distinct().toList())
                .stream().collect(Collectors.toMap(Venue::id, Venue::seatMap));

        Map<UUID, EventCounts> countsByEvent = events
                .countsFor(visible.stream().map(Event::id).toList())
                .stream().collect(Collectors.toMap(
                        EventCounts::getEventId, Function.identity()));

        List<EventDetail> items = visible.stream()
                .map(event -> {
                    EventDetail detail = EventDetail.of(event, EventPricing.of(event,
                            mapsByVenue.get(event.venueId()),
                            tiersByEvent.getOrDefault(event.id(), List.of())));
                    var counts = countsByEvent.get(event.id());
                    return counts == null ? detail
                            : detail.withCounts(counts.getSold(), counts.getRefundRequired(), counts.salesTotal());
                })
                .toList();

        Event last = visible.get(visible.size() - 1);
        return new Paged<>(items, more ? PageCursor.encode(last.createdAt(), last.id()) : null);
    }
}
