package com.eventticket.event.search.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * "This Event changed; go and read it."
 *
 * <p>An id and nothing else. The consumer re-reads the Event's current state, which is what
 * makes duplicates free and ordering irrelevant: every message means the same thing, so a retry
 * arriving after a newer change still ends on the current state.
 */
@Entity
@Table(name = "search_outbox")
public class SearchOutboxEntry {

    /** IDENTITY, because the sequence value is the queue's order and has to come from the row. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    protected SearchOutboxEntry() {}

    public SearchOutboxEntry(UUID eventId) {
        this.eventId = eventId;
    }

    public Long id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public Instant queuedAt() {
        return queuedAt;
    }
}
