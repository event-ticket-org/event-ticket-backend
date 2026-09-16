package com.eventticket.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A placement in the curated row (KB requirements/009 criterion 14): one Event, at one
 * position, over one period.
 *
 * <p>It belongs to the platform rather than to the Organization whose Event it points at,
 * which is what separates curation from advertising - an Organization cannot place itself in
 * one. That is also why it carries no {@code organizationId} and sits behind no policy.
 *
 * <p>{@code eventId} is the key itself, like every other reference here.
 */
@Entity
@Table(name = "featured_slot")
public class FeaturedSlot {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private int position;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /**
     * When the placement stops showing. Required rather than optional, so every placement has
     * an end somebody chose: a curated row with no expiry is a row nobody revisits, and the
     * Events in it are still there long after the reason was.
     */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "placed_by_user_id")
    private UUID placedByUserId;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt = Instant.now();

    protected FeaturedSlot() {}

    public FeaturedSlot(UUID eventId, int position, Instant startsAt, Instant endsAt,
                        UUID placedByUserId) {
        this.eventId = eventId;
        this.position = position;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.placedByUserId = placedByUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public int position() {
        return position;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public UUID placedByUserId() {
        return placedByUserId;
    }

    public Instant placedAt() {
        return placedAt;
    }

    /** Whether this placement is one of the ones showing at {@code at}. */
    public boolean isLiveAt(Instant at) {
        return !at.isBefore(startsAt) && at.isBefore(endsAt);
    }
}
