package com.eventticket.organization;

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
 * The link between a person and an Organization, carrying exactly one Role. A person may
 * hold memberships in several Organizations (knowledge base invariant 2).
 */
@Entity
@Table(name = "membership")
public class Membership {

    /**
     * Gate Staff can scan and nothing else. Knowledge base invariant 3 makes that a rule
     * rather than a convenience: you hand that login to a volunteer at the door, and revenue
     * must not be one wrong tap away.
     */
    public enum Role { OWNER, MANAGER, GATE_STAFF }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Membership() {}

    Membership(UUID organizationId, UUID userId, Role role) {
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

    void changeRole(Role role) {
        this.role = role;
    }
}
