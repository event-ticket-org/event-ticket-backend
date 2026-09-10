package com.eventticket.event.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
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
@Document(collection = "eventSeat")
public class EventSeat {

    /** Mirrors the contract's {@code SeatAvailability}. */
    public enum Availability { AVAILABLE, HELD, SOLD, NOT_FOR_SALE }

    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private UUID eventId;

    private String label;

    private double x;

    private double y;

    private String tierName;

    private boolean forSale = true;

    /**
     * The Seat Hold, such as it is. Not an entity and not a table: one row per seat means a
     * second hold has nowhere to exist, which is a stronger answer to KB invariant 5 than a
     * constraint forbidding a second row. Written only by the SQL functions in V5.
     *
     * <p>Expiry needs nothing to happen. A lapsed hold is a timestamp in the past, so invariant
     * 7 - "expiry releases the Event Seat with no trace" - is literally true.
     */
    private Instant heldUntil;

    private UUID heldByOrderId;

    private Instant soldAt;

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

    public UUID heldByOrderId() {
        return heldByOrderId;
    }

    /**
     * Everything a buyer needs to know about this seat, from this row alone.
     *
     * <p>No join, deliberately. The public seat map is read by anyone with a link and no tenant
     * at all, and a Ticket is not readable without one - so if "sold" were the existence of a
     * Ticket rather than a column here, the map would show sold seats as available to exactly
     * the people about to try buying them.
     */
    public Availability availability() {
        if (!forSale) {
            return Availability.NOT_FOR_SALE;
        }
        if (soldAt != null) {
            return Availability.SOLD;
        }
        return heldUntil != null && heldUntil.isAfter(Instant.now())
                ? Availability.HELD
                : Availability.AVAILABLE;
    }

    /** requirements/003 criteria 4 and 11. */
    public void offerForSale(boolean forSale) {
        this.forSale = forSale;
    }
}
