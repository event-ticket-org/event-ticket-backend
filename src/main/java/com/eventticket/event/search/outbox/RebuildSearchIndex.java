package com.eventticket.event.search.outbox;

import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.search.EventDocument;
import com.eventticket.event.search.EventDocuments;
import com.eventticket.event.search.EventSearchIndex;
import com.eventticket.organization.domain.Organization;
import com.eventticket.event.support.PageCursor;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds the whole index from Postgres, nightly, into a new index behind the alias.
 *
 * <p>This is what makes requirements/009 criterion 20 true rather than aspirational: whatever
 * serves the listing is derived and never the record, and losing it degrades ordering rather
 * than losing an Event. An index that could only be built incrementally would be a record
 * wearing a cache's name.
 *
 * <p>It is also the reconciler the incremental path needs. An outbox is at-least-once, which
 * covers a dropped connection and does not cover a call site nobody added: a document missed
 * because {@code SearchOutbox.changed} was forgotten is wrong until something rebuilds. With
 * this, "until 03:00"; without it, forever, and invisibly - the index would answer, just with
 * the wrong contents.
 *
 * <p>And it is how a mapping changes. Adding ICU folding means every document has to be
 * analysed again; doing that in place is a window with half an index behind a live alias.
 * Here the new index is filled first and the alias moves in one atomic call, so a reader sees
 * the old index or the new one and never a partial one.
 */
@Component
@ConditionalOnProperty(name = "app.search.uri")
public class RebuildSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(RebuildSearchIndex.class);

    /** Read and built a page at a time, so a large catalogue is not a large heap. */
    private static final int PAGE = 500;

    private final EventRepository events;
    private final EventDocuments documents;
    private final EventSearchIndex index;

    public RebuildSearchIndex(EventRepository events, EventDocuments documents,
                       EventSearchIndex index) {
        this.events = events;
        this.documents = documents;
        this.index = index;
    }

    /**
     * 03:20, which is after the 03:00 database backup rather than during it.
     *
     * <p>Not a fixed delay: this is the kind of work that should happen when nobody is buying,
     * and a delay measured from the last run drifts across the day until it does not.
     */
    public void nightly() {
        rebuild();
    }

    /**
     * Builds the index on boot when there is not one, and does nothing when there is.
     *
     * <p>Without this, a deployment whose Events all predate the outbox has an empty index
     * until the first edit or until 03:20 - whichever comes first. That is exactly what
     * happened on the first deployment of this feature: ten published Events, an empty outbox
     * because none of them had been touched since it existed, a drain that returned early, and
     * no index at all. Every part behaved as written; the gap was that nothing was responsible
     * for the first one.
     *
     * <p>Only when the alias is missing, so an ordinary restart costs one HEAD request. That
     * also covers the case the volume is disposable *because* of - losing the data directory is
     * now a restart rather than a day of degraded search.
     *
     * <p>After the context is ready rather than during startup, so a slow rebuild delays
     * nothing that a health check is waiting on, and failures are swallowed: a search cluster
     * that is not up yet must not stop an application whose listing works without it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void buildOnFirstStart() {
        try {
            if (index.ensureReady()) {
                log.info("No search index found at startup - building one from Postgres");
                rebuild();
            }
        } catch (RuntimeException e) {
            log.warn("Could not build the search index at startup, leaving it to the nightly "
                    + "rebuild: {}", e.toString());
        }
    }

    /** Also callable directly, which is what a mapping change and a first deployment need. */
    @Transactional(readOnly = true)
    public int rebuild() {
        index.ensureReady();
        Instant now = Instant.now();
        List<EventDocument> all = new java.util.ArrayList<>();
        PageCursor from = PageCursor.FIRST_ASCENDING;

        // Keyset, like every other listing here. An offset over a table being written to skips
        // and repeats rows, and a rebuild that skipped one would be a document quietly missing
        // until the next night.
        while (true) {
            List<Event> page = events.findPublicPage(now, Event.Status.PUBLISHED,
                    Organization.Status.APPROVED, PageCursor.orBeginning(null),
                    PageCursor.orEndOfTime(null), "%", "%", "%",
                    from.at(), from.id(), PageRequest.ofSize(PAGE));
            if (page.isEmpty()) {
                break;
            }
            all.addAll(documents.of(page));
            Event last = page.get(page.size() - 1);
            from = new PageCursor(last.startsAt(), last.id());
            if (page.size() < PAGE) {
                break;
            }
        }

        index.replaceAll(all);
        log.info("Rebuilt search index documents={}", all.size());
        return all.size();
    }
}
