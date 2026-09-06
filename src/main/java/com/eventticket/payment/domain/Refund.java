package com.eventticket.payment.domain;

import com.eventticket.shared.money.Money;
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
 * One attempt at giving money back, with a lifecycle rather than a flag.
 *
 * <p>requirements/008 criterion 2: a refund moves through {@code REFUND_PENDING} to
 * {@code REFUNDED} or {@code REFUND_FAILED}, confirmed by the provider. It is never modelled
 * as an instant boolean, for the same reason a payment is not (KB ADR-0002) - the money has
 * not moved when we ask, and a system that records that it has will be wrong for as long as
 * the provider takes to disagree.
 *
 * <p>Not tenant-scoped, like {@link PaymentSession} and for the same reason: a callback
 * arrives with no tenant and has to find this row before it can know whose it is. It carries
 * the organization and the buyer so the callback can adopt them.
 */
@Entity
@Table(name = "refund")
public class Refund {

    public enum Status { REFUND_PENDING, REFUNDED, REFUND_FAILED }

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

    /** The provider's handle for the reversal. A callback names this, never our id. */
    @Column(name = "provider_ref", nullable = false)
    private String providerRef;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String currency = Money.Currency.VND.name();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REFUND_PENDING;

    @Column(nullable = false)
    private String reason;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    protected Refund() {}

    public Refund(UUID orderId, UUID organizationId, UUID buyerUserId, String provider,
                  String providerRef, Money amount, String reason) {
        this.orderId = orderId;
        this.organizationId = organizationId;
        this.buyerUserId = buyerUserId;
        this.provider = provider;
        this.providerRef = providerRef;
        this.amount = amount.amount();
        this.currency = amount.currency().name();
        this.reason = reason;
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

    public Money amount() {
        return new Money(amount, Money.Currency.valueOf(currency));
    }

    public Status status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant settledAt() {
        return settledAt;
    }

    /**
     * A callback for a refund that has already settled is acknowledged and ignored, exactly as
     * one for a settled payment is. Providers re-deliver, and out of order.
     */
    public boolean isSettled() {
        return status != Status.REFUND_PENDING;
    }

    public void refunded() {
        this.status = Status.REFUNDED;
        this.settledAt = Instant.now();
    }

    /**
     * The reason is kept because somebody has to act on it: a failed refund is money the
     * platform is still holding, and "it failed" without a cause is a dead end
     * (the contract's {@code failureReason} exists for this).
     */
    public void failed(String cause) {
        this.status = Status.REFUND_FAILED;
        this.failureReason = cause;
        this.settledAt = Instant.now();
    }
}
