package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.FeaturedSlot;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.FeaturedSlotRepository;
import com.eventticket.event.support.PublicEventViews;
import com.eventticket.organization.domain.Organization;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The curated row: what a platform administrator has placed, and is showing now
 * (KB requirements/009 criterion 14).
 *
 * <p>A slot is a pointer, and the Event it points at can stop being listable after it was
 * placed - cancelled, unlisted, sold-out-and-past, or belonging to an Organization that has
 * since been rejected. So the placements are filtered by the same eligibility the listing
 * uses rather than trusted: a curated row is still a row of the public listing, and one
 * showing an Event the listing below it will not is worse than one showing nothing.
 */
@Component
public class ListFeaturedEvents {

    /**
     * Below this the row is not shown at all (criterion 16).
     *
     * <p>Three rather than the five a ranked row needs, and the difference is that a human
     * chose these. A chart of two is not a chart - nobody meant the number two. A curated row
     * of two is a person who placed two things, which is a smaller claim and a more honest
     * one; below three it stops reading as a selection and starts reading as a banner that
     * failed to load its siblings.
     */
    public static final int MINIMUM = 3;

    private final FeaturedSlotRepository slots;
    private final EventRepository events;
    private final PublicEventViews views;

    public ListFeaturedEvents(FeaturedSlotRepository slots, EventRepository events,
                       PublicEventViews views) {
        this.slots = slots;
        this.events = events;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public List<PublicEventView> list() {
        Instant now = Instant.now();
        List<FeaturedSlot> live = slots.findLiveAt(now);
        if (live.isEmpty()) {
            return List.of();
        }

        // The listing's own predicate, applied to the placed ids. Published, listed, not yet
        // started, approved Organization - the same query the listing runs, narrowed to these.
        Map<UUID, Event> eligible = events
                .findPublicByIds(live.stream().map(FeaturedSlot::eventId).distinct().toList(),
                        now, Event.Status.PUBLISHED, Organization.Status.APPROVED)
                .stream().collect(Collectors.toMap(Event::id, Function.identity()));

        List<Event> ordered = live.stream()
                .map(slot -> eligible.get(slot.eventId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        // Checked after the filter, not before it: five placements of which two have been
        // cancelled is a row of three, and it is the three that decide whether to draw it.
        if (ordered.size() < MINIMUM) {
            return List.of();
        }
        return views.of(ordered, now);
    }
}
