package com.eventticket.checkout.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.eventticket.shared.money.Money;
import java.util.UUID;

/**
 * One seat on an Order, at the price it was held at.
 *
 * <p>The label and tier are copied rather than looked up, and so is the amount. KB invariant
 * 10 says a price change applies only to later sales; nothing has to arrange that, because a
 * row that recorded its own amount cannot be reached by an edit to the tier it came from.
 */
@Document(collection = "orderSeat")
public class OrderSeat {

    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private UUID buyerUserId;

    private UUID orderId;

    private UUID eventSeatId;

    private String label;

    private String tierName;

    private long amount;

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
