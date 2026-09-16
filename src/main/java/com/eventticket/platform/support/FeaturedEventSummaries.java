package com.eventticket.platform.support;

import com.eventticket.api.model.PublicEventSummary;
import com.eventticket.event.domain.FeaturedSlot;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.support.PublicEventViews;
import com.eventticket.event.web.EventMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The Events behind a set of slots, as the summaries the administrative row draws.
 *
 * <p><strong>Without the listing's eligibility predicate</strong>, which is the one way this
 * differs from the public row and the reason it is a separate call. A slot whose Event has
 * since been cancelled disappears from {@code /public/featured-events} and must not disappear
 * here: an administrator can only remove a placement that stopped working if they can see it.
 * The public row answers "what should a visitor see"; this answers "what did somebody place".
 *
 * <p>Row-level security still applies and still admits these. Every Event that could be placed
 * was published, and a published Event stays readable with no tenant even once it is cancelled
 * - which is the same rule that keeps a cancelled show's link working.
 *
 * <p>One query for the whole row rather than one per slot.
 */
@Component
public class FeaturedEventSummaries {

    private final EventRepository events;
    private final PublicEventViews views;

    public FeaturedEventSummaries(EventRepository events, PublicEventViews views) {
        this.events = events;
        this.views = views;
    }

    public Map<UUID, PublicEventSummary> of(List<FeaturedSlot> slots) {
        List<UUID> ids = slots.stream().map(FeaturedSlot::eventId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return views.of(events.findAllById(ids), Instant.now()).stream()
                .collect(Collectors.toMap(view -> view.event().id(), EventMapper::toSummaryDto));
    }
}
