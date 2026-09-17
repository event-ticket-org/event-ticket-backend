package com.eventticket.event.search.outbox;

import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.search.EventDocument;
import com.eventticket.event.search.EventDocuments;
import com.eventticket.event.search.EventSearchIndex;
import com.eventticket.event.search.SearchUnavailableException;
import com.eventticket.organization.domain.Organization;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox: re-read each Event, then index it or delete it.
 *
 * <p><strong>Convergent, not ordered.</strong> A row says only that an Event changed, so the
 * current state is read here rather than carried from the writer. Duplicates land the same
 * document, an out-of-order retry still ends on the latest state, and an intermediate state
 * that was never indexed is not a loss because the index only cares about now.
 *
 * <p><strong>Index or delete, decided by the same predicate the listing uses.</strong> An Event
 * that has been cancelled, unlisted, or has started is not one the listing shows, so its
 * document goes. Without that branch, unpublishing an Event would leave it searchable forever
 * and the index would only ever grow.
 *
 * <p>Single-threaded, like {@code DispatchPendingEmails}. Two workers re-reading the same Event
 * could interleave and write the older state last; one worker cannot. {@code FOR UPDATE SKIP
 * LOCKED} is the answer when one worker stops being enough, and it is not yet.
 */
@Component
@ConditionalOnProperty(name = "app.search.uri")
public class IndexPendingEvents {

    private static final Logger log = LoggerFactory.getLogger(IndexPendingEvents.class);

    /** Events per run, not rows: ten edits to one Event are one of these. */
    private static final int BATCH = 200;

    private final SearchOutboxRepository entries;
    private final EventRepository events;
    private final EventDocuments documents;
    private final EventSearchIndex index;

    public IndexPendingEvents(SearchOutboxRepository entries, EventRepository events,
                       EventDocuments documents, EventSearchIndex index) {
        this.entries = entries;
        this.events = events;
        this.documents = documents;
        this.index = index;
    }

    /**
     * Frequent, because the delay is visible: somebody publishes an Event and then searches for
     * it. Two seconds is under the time it takes to type a query, and the work when the outbox
     * is empty is one indexed count.
     */
    @Transactional
    public void drain() {
        List<SearchOutboxRepository.Pending> pending =
                entries.findPending(PageRequest.ofSize(BATCH));
        if (pending.isEmpty()) {
            return;
        }

        List<UUID> ids = pending.stream()
                .map(SearchOutboxRepository.Pending::getEventId).toList();

        // The listing's own eligibility, so the index can never hold something the listing
        // would refuse to show.
        Map<UUID, Event> listable = events
                .findPublicByIds(ids, Instant.now(), Event.Status.PUBLISHED,
                        Organization.Status.APPROVED)
                .stream().collect(Collectors.toMap(Event::id, Function.identity()));

        List<Event> toIndex = new ArrayList<>();
        List<UUID> toDelete = new ArrayList<>();
        for (UUID id : ids) {
            Event event = listable.get(id);
            if (event == null) {
                toDelete.add(id);
            } else {
                toIndex.add(event);
            }
        }

        try {
            // Idempotent, and called here rather than at startup: an application that booted
            // before the cluster did would otherwise need a retry nobody wrote, and a cluster
            // that lost its index would need a restart to get one back.
            index.ensureReady();
            List<EventDocument> built = documents.of(toIndex);
            index.index(built);
            index.delete(toDelete);
        } catch (SearchUnavailableException e) {
            // The rows stay. The next run tries again, and the nightly rebuild would catch it
            // anyway - so a cluster that is down costs staleness rather than data.
            log.warn("Search index unavailable, leaving {} events queued: {}",
                    ids.size(), e.getMessage());
            throw e;
        }

        // Only up to the id each Event was accounted for at. A row written while this was
        // working is a later id and survives to the next run.
        pending.forEach(entry -> entries.deleteHandled(entry.getEventId(), entry.getThroughId()));
        log.debug("Search drain indexed={} deleted={}", toIndex.size(), toDelete.size());
    }
}
