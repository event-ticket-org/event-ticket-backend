package com.eventticket.admission.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
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
@Document(collection = "scan")
public class Scan {

    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private UUID eventId;

    /** Null when the code resolved to nothing. The attempt is still recorded. */
    private UUID ticketId;

    private UUID scannedByUserId;

    private String deviceId;

    private ScanOutcome outcome;

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
