package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.TrendingEventRepository;
import com.eventticket.event.support.PublicEventViews;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ranked row: Events by Tickets sold in the last week, and by nothing else
 * (KB requirements/009 criterion 15).
 *
 * <p><strong>Sales, because sales are the only honest signal here.</strong> There is no view
 * tracking in this system and none is being added: a write on every public page load is a cost
 * and a decision about what is stored about people, and neither is worth paying to rank ten
 * events. What the database already knows is what people bought, which is a fact about the
 * Event rather than about the platform's own traffic.
 *
 * <p><strong>The rank is published and the figures are not.</strong> A position says one Event
 * outsold another this week. A count says what an Organization took, across Organizations, to
 * anybody who loads the page - and sales figures are restricted inside an Organization already
 * (requirements/007 criterion 13). The query returns the counts because it has to sort by
 * them; nothing carries them past this class.
 */
@Component
public class ListTrendingEvents {

    /**
     * Below this the row is not shown (criterion 16). A chart of two is not a chart: the
     * ranking numerals beside it are the whole point, and 1 and 2 with nothing under them
     * reads as a list that failed to finish loading.
     */
    public static final int MINIMUM = 5;

    /** Ticketbox shows ten. Past that a "trending" row is a second listing with numbers on it. */
    private static final int SIZE = 10;

    /**
     * How far back demand counts for.
     *
     * <p>Long enough that a quiet Tuesday does not empty the chart, short enough that it is
     * about now: an event that sold out three months ago is not trending, it is history, and a
     * window wide enough to include it would rank the back catalogue forever.
     */
    private static final Duration WINDOW = Duration.ofDays(7);

    private final TrendingEventRepository trending;
    private final EventRepository events;
    private final PublicEventViews views;

    public ListTrendingEvents(TrendingEventRepository trending, EventRepository events,
                       PublicEventViews views) {
        this.trending = trending;
        this.events = events;
        this.views = views;
    }

    /** In rank order. The caller numbers them from one; nothing here says how many of anything. */
    @Transactional(readOnly = true)
    public List<PublicEventView> list() {
        Instant now = Instant.now();
        List<UUID> ranked = trending.findTrending(now.minus(WINDOW), now, SIZE).stream()
                .map(TrendingEventRepository.Ranked::getEventId)
                .toList();

        if (ranked.size() < MINIMUM) {
            return List.of();
        }

        // The ranking query already applies the listing's eligibility, so this read is for the
        // rows themselves rather than for a second opinion about which ones qualify.
        Map<UUID, Event> byId = events.findAllById(ranked).stream()
                .collect(Collectors.toMap(Event::id, Function.identity()));
        List<Event> ordered = ranked.stream().map(byId::get)
                .filter(java.util.Objects::nonNull).toList();

        return ordered.size() < MINIMUM ? List.of() : views.of(ordered, now);
    }
}
