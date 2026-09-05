package com.eventticket.admission.domain;

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
 * One attempt at a door, whatever came of it (requirements/007 criterion 6, KB invariant 14).
 *
 * <p>Refusals are recorded as carefully as admissions, and codes that were never ours are
 * recorded too. Somebody standing at a gate with a ticket that will not work is exactly the
 * situation this row exists to explain afterwards, and it is the only trace of a code being
 * tried repeatedly by someone who should not have it.
 */
@Entity
@Table(name = "scan")
public class Scan {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Null when the code resolved to nothing. The attempt is still recorded. */
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "scanned_by_user_id", nullable = false)
    private UUID scannedByUserId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanOutcome outcome;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected Scan() {}

    public Scan(UUID organizationId, UUID eventId, UUID ticketId, UUID scannedByUserId,
                String deviceId, ScanOutcome outcome) {
        this.organizationId = organizationId;
        this.eventId = eventId;
        this.ticketId = ticketId;
        this.scannedByUserId = scannedByUserId;
        this.deviceId = deviceId;
        this.outcome = outcome;
    }

    public UUID id() {
        return id;
    }

    public ScanOutcome outcome() {
        return outcome;
    }
}
