package com.eventticket.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A seat as it exists for one Event: the copy taken at publish (KB invariant 8).
 *
 * <p>A row rather than a line in a document, because from here on it is the thing a Seat Hold
 * locks, a Ticket names and a Scan admits. KB invariant 5 makes "at most one active hold per
 * seat" a database constraint, and a constraint needs something to constrain.
 *
 * <p>Its label, position and tier are immutable - the trigger {@code event_seat_frozen}
 * refuses to change them once the Event is published. {@code forSale} is not: availability is
 * live, and requirements/003 criterion 11 lets capacity grow.
 */
@Entity
@Table(name = "event_seat")
public class EventSeat {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @Column(name = "tier_name", nullable = false)
    private String tierName;

    @Column(name = "for_sale", nullable = false)
    private boolean forSale = true;

    protected EventSeat() {}

    public EventSeat(UUID organizationId, UUID eventId, String label, double x, double y, String tierName) {
        this.organizationId = organizationId;
        this.eventId = eventId;
        this.label = label;
        this.x = x;
        this.y = y;
        this.tierName = tierName;
    }

    public UUID id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public String label() {
        return label;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public String tierName() {
        return tierName;
    }

    public boolean isForSale() {
        return forSale;
    }

    /** requirements/003 criteria 4 and 11. */
    public void offerForSale(boolean forSale) {
        this.forSale = forSale;
    }
}
