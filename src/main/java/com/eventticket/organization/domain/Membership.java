package com.eventticket.organization.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.UUID;

/**
 * The link between a person and an Organization, carrying exactly one Role. A person may
 * hold memberships in several Organizations (knowledge base invariant 2).
 */
@Document(collection = "membership")
public class Membership {

    /**
     * Gate Staff can scan and nothing else. Knowledge base invariant 3 makes that a rule
     * rather than a convenience: you hand that login to a volunteer at the door, and revenue
     * must not be one wrong tap away.
     */
    public enum Role { OWNER, MANAGER, GATE_STAFF }

    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private UUID userId;

    private Role role;

    private Instant createdAt = Instant.now();

    protected Membership() {}

    public Membership(UUID organizationId, UUID userId, Role role) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID userId() {
        return userId;
    }

    public Role role() {
        return role;
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }

    /** May create and run events: Owner and Manager, never Gate Staff. */
    public boolean canManageEvents() {
        return role == Role.OWNER || role == Role.MANAGER;
    }

    public void changeRole(Role role) {
        this.role = role;
    }
}
