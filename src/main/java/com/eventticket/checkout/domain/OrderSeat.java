package com.eventticket.checkout.domain;

import com.eventticket.shared.money.Money;
import java.util.UUID;

/**
 * One seat on an Order, at the price it was held at.
 *
 * <p>The label and tier are copied rather than looked up, and so is the amount. KB invariant
 * 10 says a price change applies only to later sales; nothing has to arrange that, because a
 * record that carries its own amount cannot be reached by an edit to the tier it came from.
 *
 * <p><strong>Embedded in {@link Order} rather than stored in its own collection.</strong> It
 * answers every question in the design's favour: never read without its Order, bounded at one
 * to eight, immutable once written, and carrying no constraint that needs a collection to
 * enforce. This is the clearest embed in the system.
 *
 * <p>Four fields stayed behind with the table, and what they were for is the interesting part.
 * {@code id} is gone because an embedded document needs no key of its own - its identity is its
 * position under a parent that already has one. {@code orderId} is gone because it <em>was</em>
 * the join, and there is no longer a join to express.
 *
 * <p>{@code organizationId} and {@code buyerUserId} are the two worth pausing on. They were
 * never really the seat's - they were copied onto every row so that the {@code order_seat_access}
 * policy had something local to test, because a row-level security policy cannot see a parent.
 * An embedded document is only ever reached through its parent, so it inherits whatever scoped
 * the parent. Embedding removed a tenancy field rather than needing one, which is the tenancy
 * argument for embedding and the one nobody mentions.
 */
public class OrderSeat {

    private UUID eventSeatId;

    private String label;

    private String tierName;

    private long amount;

    private String currency = Money.Currency.VND.name();

    protected OrderSeat() {}

    public OrderSeat(UUID eventSeatId, String label, String tierName, Money price) {
        this.eventSeatId = eventSeatId;
        this.label = label;
        this.tierName = tierName;
        this.amount = price.amount();
        this.currency = price.currency().name();
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
