package com.eventticket.checkout.domain;

import com.eventticket.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One seat on an Order, at the price it was held at.
 *
 * <p>The label and tier are copied rather than looked up, and so is the amount. KB invariant
 * 10 says a price change applies only to later sales; nothing has to arrange that, because a
 * row that recorded its own amount cannot be reached by an edit to the tier it came from.
 */
@Entity
@Table(name = "order_seat")
public class OrderSeat {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "event_seat_id", nullable = false)
    private UUID eventSeatId;

    @Column(nullable = false)
    private String label;

    @Column(name = "tier_name", nullable = false)
    private String tierName;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String currency = Money.Currency.VND.name();

    protected OrderSeat() {}

    public OrderSeat(UUID organizationId, UUID buyerUserId, UUID orderId, UUID eventSeatId,
                     String label, String tierName, Money price) {
        this.organizationId = organizationId;
        this.buyerUserId = buyerUserId;
        this.orderId = orderId;
        this.eventSeatId = eventSeatId;
        this.label = label;
        this.tierName = tierName;
        this.amount = price.amount();
        this.currency = price.currency().name();
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID eventSeatId() {
        return eventSeatId;
    }

    public String label() {
        return label;
    }

    public String tierName() {
        return tierName;
    }

    public Money price() {
        return new Money(amount, Money.Currency.valueOf(currency));
    }
}
