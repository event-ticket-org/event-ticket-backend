package com.eventticket.payment.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.eventticket.shared.money.Money;
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
@Document(collection = "refund")
public class Refund {

    public enum Status { REFUND_PENDING, REFUNDED, REFUND_FAILED }

    @Id
    private UUID id = UUID.randomUUID();

    private UUID orderId;

    private UUID organizationId;

    private UUID buyerUserId;

    private String provider;

    /**
     * The provider's handle for the reversal. A callback names this, never our id.
     *
     * <p>Null on a refund that was refused before any provider was asked - see
     * {@link #refused}. There is no handle for a request nobody made.
     */
    private String providerRef;

    private long amount;

    private String currency = Money.Currency.VND.name();

    private Status status = Status.REFUND_PENDING;

    private String reason;

    private String failureReason;

    private Instant createdAt = Instant.now();

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

    /**
     * A refund that was refused before a provider was involved, recorded so that somebody can
     * see it (requirements/008 criterion 7).
     *
     * <p>Only the bulk path needs this. A refund asked for through the API is refused with a
     * 409 and a message, and the caller is right there reading it; a cancellation refunds
     * hundreds of Orders with nobody watching each one, and a refusal that went only to the
     * log would leave that Order reported as "still going" for ever.
     */
    public static Refund refused(UUID orderId, UUID organizationId, UUID buyerUserId,
                                 String provider, Money amount, String reason, String cause) {
        Refund refund = new Refund(orderId, organizationId, buyerUserId, provider, null,
                amount, reason);
        refund.failed(cause);
        return refund;
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

    /**
     * Settled, and settled as having worked - which is the state a provider can take back
     * (requirements/008 criterion 11). A failure arriving for a refund that already failed is
     * the ordinary re-delivery and changes nothing.
     */
    public boolean isRefunded() {
        return status == Status.REFUNDED;
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
