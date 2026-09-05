package com.eventticket.organization.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
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
 * The tenant. Owns venues, events, members and payment configuration.
 *
 * <p>An Organization may build events while unapproved and may not publish one
 * (requirements/001 criteria 3-5). That gate is the platform's fraud control, and it lives
 * on the entity rather than in a use case so that every caller meets the same answer.
 */
@Entity
@Table(name = "organization")
public class Organization {

    public enum Status { PENDING_APPROVAL, APPROVED, REJECTED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING_APPROVAL;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Organization() {}

    public Organization(String name) {
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Status status() {
        return status;
    }

    public String decisionReason() {
        return decisionReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isApproved() {
        return status == Status.APPROVED;
    }

    /**
     * Guard for anything an unapproved Organization may not do. The message says what is
     * wrong and what happens next, rather than a bare refusal (requirements/001 criterion 5).
     */
    public void requireApproved() {
        if (!isApproved()) {
            throw new ApiException(ErrorCodes.ORGANIZATION_NOT_APPROVED,
                    "This organization is awaiting approval and cannot sell tickets yet. "
                            + "You can keep preparing events; an administrator reviews new "
                            + "organizations and the owner is emailed once a decision is made.");
        }
    }

    /**
     * Approval and rejection are the platform's decisions, not the Organization's own, so
     * these are reachable from the platform package. They are named to say so: nothing inside
     * this package should be calling them.
     */
    public void approveByPlatform() {
        this.status = Status.APPROVED;
        this.decisionReason = null;
    }

    public void rejectByPlatform(String reason) {
        this.status = Status.REJECTED;
        this.decisionReason = reason;
    }
}
