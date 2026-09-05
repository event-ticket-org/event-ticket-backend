package com.eventticket.checkout.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
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
 * What a buyer is buying, and the clock they are buying it against.
 *
 * <p>The Order owns the deadline; the seats own the holds. {@code holdExpiresAt} is what the
 * buyer is shown counting down (requirements/004 criterion 8) and what a payment attempt is
 * checked against, while the seats themselves carry the hold that actually excludes anyone
 * else. Two records of one fact, deliberately: the seat's is authoritative for availability
 * and the Order's is what a human is looking at.
 */
@Entity
@Table(name = "ticket_order")
public class Order {

    public enum Status { AWAITING_PAYMENT, PAID, EXPIRED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** A User, not a Member. Buyers are almost never members of the Organization they buy from. */
    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AWAITING_PAYMENT;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private String currency = Money.Currency.VND.name();

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    /**
     * requirements/005 criterion 9. A confirmation that arrives after the holds lapsed must
     * not silently keep the money. There is no refund machinery until requirements/008, so
     * this is the flag that phase - or a person - acts on.
     */
    @Column(name = "refund_required", nullable = false)
    private boolean refundRequired;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Order() {}

    public Order(UUID organizationId, UUID eventId, UUID buyerUserId, Money total, Instant holdExpiresAt) {
        this.organizationId = organizationId;
        this.eventId = eventId;
        this.buyerUserId = buyerUserId;
        this.totalAmount = total.amount();
        this.currency = total.currency().name();
        this.holdExpiresAt = holdExpiresAt;
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID buyerUserId() {
        return buyerUserId;
    }

    public Status status() {
        return status;
    }

    public Money total() {
        return new Money(totalAmount, Money.Currency.valueOf(currency));
    }

    /** Null once paid: the contract says the countdown stops meaning anything at that point. */
    public Instant holdExpiresAt() {
        return status == Status.PAID ? null : holdExpiresAt;
    }

    public boolean refundRequired() {
        return refundRequired;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isAwaitingPayment() {
        return status == Status.AWAITING_PAYMENT;
    }

    public boolean holdsAreAlive() {
        return isAwaitingPayment() && holdExpiresAt != null && holdExpiresAt.isAfter(Instant.now());
    }

    /**
     * requirements/005 criterion 11: an Order has many payment attempts and at most one
     * success. Refusing here is what keeps a second attempt from being started against an
     * Order whose seats somebody else already has.
     */
    public void requirePayable() {
        if (status == Status.PAID) {
            throw new ApiException(ErrorCodes.ORDER_ALREADY_PAID, "This order has already been paid.");
        }
        if (!isAwaitingPayment()) {
            throw new ApiException(ErrorCodes.HOLD_EXPIRED,
                    "This order is " + status.name().toLowerCase() + " and can no longer be paid.");
        }
        if (!holdsAreAlive()) {
            throw new ApiException(ErrorCodes.HOLD_EXPIRED,
                    "The seats held for this order have been released. Choose your seats again.");
        }
    }

    public void paid() {
        this.status = Status.PAID;
        this.paidAt = Instant.now();
    }

    /** requirements/004 criterion 11. Abandoning is the buyer's own decision, not an expiry. */
    public void cancel() {
        if (status == Status.PAID) {
            throw new ApiException(ErrorCodes.ORDER_ALREADY_PAID,
                    "This order has been paid and cannot be abandoned.");
        }
        this.status = Status.CANCELLED;
        this.holdExpiresAt = null;
    }

    /** The holds lapsed before the money arrived. The seats are gone; the payment is not ours. */
    public void expiredWithPaymentTaken() {
        this.status = Status.EXPIRED;
        this.refundRequired = true;
        this.holdExpiresAt = null;
    }

    public void expired() {
        this.status = Status.EXPIRED;
        this.holdExpiresAt = null;
    }

    public Order requireBuyerOrOrganization(UUID userId, UUID organizationId) {
        if (!buyerUserId.equals(userId) && !this.organizationId.equals(organizationId)) {
            throw ApiException.notFound("Order");
        }
        return this;
    }
}
