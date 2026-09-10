package com.eventticket.shared.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.UUID;

/** One recorded action. Append-only: there is deliberately no setter and no delete path. */
@Document(collection = "auditEntry")
public class AuditEntry {

    /**
     * A UUID, where Postgres used {@code BIGSERIAL}. MongoDB has no auto-increment: its two
     * answers are an {@code ObjectId}, whose leading four bytes are a timestamp and which is
     * therefore roughly monotonic, or a sequence collection bumped with {@code findAndModify} -
     * one extra round trip and one contended document per insert.
     *
     * <p>Neither is needed here. Nothing reads this id and nothing orders by it; the audit
     * trail is ordered by {@code occurredAt}, which is what {@code audit_entry_org_idx} sorted
     * on in Postgres too. The sequence was never carrying meaning, only supplying a key.
     */
    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private UUID actorUserId;

    private String action;

    private String subject;

    private Instant occurredAt = Instant.now();

    protected AuditEntry() {}

    public AuditEntry(UUID organizationId, UUID actorUserId, String action, String subject) {
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
