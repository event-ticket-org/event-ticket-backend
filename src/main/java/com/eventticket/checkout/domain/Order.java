package com.eventticket.checkout.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
@Document(collection = "ticketOrder")
public class Order {

    public enum Status { AWAITING_PAYMENT, PAID, EXPIRED, CANCELLED, REFUNDED }

    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private UUID eventId;

    /** A User, not a Member. Buyers are almost never members of the Organization they buy from. */
    private UUID buyerUserId;

    private Status status = Status.AWAITING_PAYMENT;

    private long totalAmount;

    private String currency = Money.Currency.VND.name();

    private Instant holdExpiresAt;

    /**
     * requirements/005 criterion 9. A confirmation that arrives after the holds lapsed must
     * not silently keep the money: the seats went back on sale and the payment did not, so
     * this Order is EXPIRED and holding money anyway.
     *
     * <p>requirements/008 is what finally acts on it. Until then nothing read this column,
     * which is a state the platform could reach and not leave.
     */
    private boolean refundRequired;

    private Instant paidAt;

    private Instant createdAt = Instant.now();

    /**
     * The seats, embedded (see {@link OrderSeat}). Kept sorted by label, which is what
     * {@code findByOrderIdOrderByLabelAsc} used to ask the database for - eight seats is small
     * enough that sorting once on write beats sorting on every read, and the database can no
     * longer be asked to sort a nested array on the way out.
     */
    private List<OrderSeat> seats = new ArrayList<>();

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

    public List<OrderSeat> seats() {
        return List.copyOf(seats);
    }

    /**
     * Set once, when the Order is created and its seats have just been held.
     *
     * <p>Deliberately not {@code addSeat}. An Order's seats are decided in a single act at
     * checkout and never afterwards, and a method that appended would invite a second write to
     * a document whose whole justification for being one document is that it is written once.
     */
    public void holdSeats(List<OrderSeat> chosen) {
        this.seats = new ArrayList<>(chosen);
        this.seats.sort(Comparator.comparing(OrderSeat::label));
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

    /**
     * requirements/008 criterion 11. A refund the provider settled and then reversed leaves
     * this Order exactly where a late payment leaves one: holding money for seats nobody got.
     * That is what {@code refundRequired} already means, which is why this sets the flag it
     * has rather than inventing a second one.
     *
     * <p>The status is deliberately left as REFUNDED. A refund <em>was</em> attempted and
     * <em>was</em> reported settled; that happened, and rewriting it to PAID would erase the
     * only clue to why a buyer is holding an email saying they were refunded. What makes the
     * Order actionable again is the flag, which {@code requireRefundable} now reads.
     */
    public void refundWasReversed() {
        this.refundRequired = true;
    }

    /**
     * requirements/008 criteria 1 and 2. Refusing here is the whole of what "refundable"
     * means, and the interesting case is the one that is not PAID: an Order whose payment
     * landed after its holds lapsed is EXPIRED and is holding money anyway. Requiring PAID
     * would refuse precisely the case this phase was brought forward to fix.
     *
     * <p>A redeemed Ticket is the other refusal (criterion 3) and is not checked here - this
     * class cannot see Tickets, and asking the database rather than inverting a module
     * dependency is the rule. {@code RefundOrder} makes that check.
     */
    public void requireRefundable() {
        // Refunded and still holding the money is the one case where "already refunded" is
        // not an answer: a provider settled and then took it back (requirements/008 criterion
        // 11), so the flag is set and a second attempt is the entire point of setting it. A
        // flag somebody can see and cannot act on is the failure criterion 10 warns about.
        if (status == Status.REFUNDED && !refundRequired) {
            throw new ApiException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                    "This order has already been refunded.");
        }
        if (status != Status.PAID && !refundRequired) {
            throw new ApiException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                    "Nothing was paid for this order, so there is nothing to refund.");
        }
    }

    /**
     * The money is on its way back. {@code refundRequired} is cleared with it: the flag asks
     * for exactly this, and an Order that still asked for a refund after being refunded would
     * keep appearing on the list of Orders holding money the platform should not keep.
     */
    public void refunded() {
        this.status = Status.REFUNDED;
        this.refundRequired = false;
    }

    public Order requireBuyerOrOrganization(UUID userId, UUID organizationId) {
        if (!buyerUserId.equals(userId) && !this.organizationId.equals(organizationId)) {
            throw ApiException.notFound("Order");
        }
        return this;
    }
}
