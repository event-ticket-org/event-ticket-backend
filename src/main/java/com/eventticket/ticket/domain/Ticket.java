package com.eventticket.ticket.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One admission for one person, once (KB invariant 13).
 *
 * <p>Deliberately anonymous: no attendee name, no detail (requirements/004 criterion 13). A
 * Ticket knows the seat it admits to and nothing about who will sit in it, which is also why
 * it can be handed to somebody else without the system needing to care.
 *
 * <p>{@code codeLookup} is not the code. It is 128 random bits that find this row; the code
 * shown to the buyer is assembled from it with a key that lives in the environment, so a
 * leaked database is not a set of working tickets. Reissuing replaces the lookup, and the
 * previous code stops resolving to anything at all.
 */
@Entity
@Table(name = "ticket")
public class Ticket {

    public enum Status { VALID, REDEEMED, VOID }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_seat_id", nullable = false)
    private UUID eventSeatId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Column(name = "tier_name", nullable = false)
    private String tierName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VALID;

    @Column(name = "code_lookup", nullable = false)
    private String codeLookup;

    @Column(name = "code_version", nullable = false)
    private short codeVersion;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    /**
     * requirements/007 criterion 5. The device matters as much as the instant: together they
     * are how staff tell "you already went in" from "somebody else used your ticket".
     */
    @Column(name = "redeemed_by_user_id")
    private UUID redeemedByUserId;

    @Column(name = "redeemed_device_id")
    private String redeemedDeviceId;

    protected Ticket() {}

    public Ticket(UUID organizationId, UUID buyerUserId, UUID orderId, UUID eventId,
                  UUID eventSeatId, String seatLabel, String tierName,
                  String codeLookup, short codeVersion) {
        this.organizationId = organizationId;
        this.buyerUserId = buyerUserId;
        this.orderId = orderId;
        this.eventId = eventId;
        this.eventSeatId = eventSeatId;
        this.seatLabel = seatLabel;
        this.tierName = tierName;
        this.codeLookup = codeLookup;
        this.codeVersion = codeVersion;
    }

    public UUID id() {
        return id;
    }

    public UUID buyerUserId() {
        return buyerUserId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID eventSeatId() {
        return eventSeatId;
    }

    public String seatLabel() {
        return seatLabel;
    }

    public String tierName() {
        return tierName;
    }

    public Status status() {
        return status;
    }

    public String codeLookup() {
        return codeLookup;
    }

    public short codeVersion() {
        return codeVersion;
    }

    public Instant redeemedAt() {
        return redeemedAt;
    }

    public String redeemedDeviceId() {
        return redeemedDeviceId;
    }

    /**
     * requirements/008 criterion 4. A refunded Order's Tickets stop admitting anyone, and the
     * door says so in its own words rather than reporting an unknown code - the person holding
     * it was sold something real and is entitled to be told what happened to it.
     *
     * <p><b>A redeemed Ticket is left alone.</b> Somebody has already walked through the door,
     * and nothing takes that back (criterion 3, KB invariant 21) - so REDEEMED is a final
     * state and this is a no-op against one.
     *
     * <p>The guard is here rather than in the caller because of what happened without it:
     * cancelling an Event voids every Ticket first and refunds afterwards, so a blanket void
     * turned a redeemed Ticket into a void one, and the refund check that asks "has anybody
     * been let in?" then found nobody and refunded an Order for somebody who had already been
     * admitted. One rule, on the thing it is about, cannot be got wrong by the next caller.
     */
    public void voided() {
        if (status == Status.VALID) {
            this.status = Status.VOID;
        }
    }

    public boolean isVoid() {
        return status == Status.VOID;
    }
}
