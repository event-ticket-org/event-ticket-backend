package com.eventticket.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One recorded action. Append-only: there is deliberately no setter and no delete path. */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false)
    private String action;

    private String subject;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected AuditEntry() {}

    AuditEntry(UUID organizationId, UUID actorUserId, String action, String subject) {
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.subject = subject;
    }

    public Long id() {
        return id;
    }

    public String action() {
        return action;
    }

    public String subject() {
        return subject;
    }

    public UUID actorUserId() {
        return actorUserId;
    }
}
