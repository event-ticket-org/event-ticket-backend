package com.eventticket.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One attempt at paying for an Order. KB invariant 18: an Order has many of these and at most
 * one success.
 *
 * <p>Not tenant-scoped, and that is the point of the two identifier columns on it. A webhook
 * arrives with no tenant at all and must find the session before it can know whose it is;
 * scoping this table would make that a chicken and egg solvable only by punching a hole in the
 * policies. It is addressed by an unguessable identifier and is platform plumbing rather than a
 * tenant's queryable data - the same category as a refresh token.
 */
@Entity
@Table(name = "payment_session")
public class PaymentSession {

    public enum Status { AWAITING_PAYMENT, PAID, FAILED, EXPIRED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(nullable = false)
    private String provider;

    /** The provider's own handle for this attempt. A confirmation names this, never our id. */
    @Column(name = "provider_ref", nullable = false)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AWAITING_PAYMENT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "next_action", nullable = false)
    private NextAction nextAction;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PaymentSession() {}

    public PaymentSession(UUID orderId, UUID organizationId, UUID buyerUserId, String provider,
                          String providerRef, NextAction nextAction, Instant expiresAt) {
        this.orderId = orderId;
        this.organizationId = organizationId;
        this.buyerUserId = buyerUserId;
        this.provider = provider;
        this.providerRef = providerRef;
        this.nextAction = nextAction;
        this.expiresAt = expiresAt;
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID buyerUserId() {
        return buyerUserId;
    }

    public String provider() {
        return provider;
    }

    public String providerRef() {
        return providerRef;
    }

    public Status status() {
        return status;
    }

    public NextAction nextAction() {
        return nextAction;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * requirements/005 criterion 6: a confirmation for an already-settled session is
     * acknowledged and ignored, not treated as an error. Providers re-deliver, and out of
     * order, and after the buyer closed the tab.
     */
    public boolean isSettled() {
        return status != Status.AWAITING_PAYMENT;
    }

    public void paid() {
        this.status = Status.PAID;
    }

    public void failed() {
        this.status = Status.FAILED;
    }

    public void expired() {
        this.status = Status.EXPIRED;
    }
}
