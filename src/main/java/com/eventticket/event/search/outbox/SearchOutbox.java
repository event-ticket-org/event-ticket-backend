package com.eventticket.event.search.outbox;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Where a use case says that an Event changed.
 *
 * <p>One line at each call site, inside the caller's transaction, so a notification cannot
 * exist for a change that rolled back and a change cannot commit without its notification.
 *
 * <p><strong>It is called explicitly rather than inferred from a JPA lifecycle callback.</strong>
 * An {@code @PostUpdate} listener would catch every change to {@code Event} and none to
 * {@code PricingTier}, so the cheapest price - which is on the document - would silently stop
 * being updated while everything else kept working. A rule that covers most of the cases
 * automatically is worse than one that covers none, because nobody looks for the exceptions.
 *
 * <p>Explicit calls can be forgotten, and that is what the nightly rebuild is for. A missed
 * call site means one Event is stale until 03:00, not stale forever - which is the difference
 * between a bug and an outage, and the reason the rebuild exists before anything needs it.
 *
 * <p>It writes even when no search cluster is configured. The rows are the backlog for the day
 * one is, and they are an id and a timestamp; the alternative is switching search on and
 * finding the index empty until the first edit of every Event.
 */
@Component
public class SearchOutbox {

    private final SearchOutboxRepository entries;

    public SearchOutbox(SearchOutboxRepository entries) {
        this.entries = entries;
    }

    /** Records that this Event's public representation may have changed. */
    public void changed(UUID eventId) {
        entries.save(new SearchOutboxEntry(eventId));
    }
}
