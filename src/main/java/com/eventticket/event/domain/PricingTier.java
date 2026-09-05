package com.eventticket.event.domain;

import com.eventticket.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * What one Pricing Tier costs for one Event. The tier's *name* comes from the Seat Map, which
 * is a document and has nothing to point at, so the link is by name and there is no foreign
 * key to keep.
 *
 * <p>A null amount means the tier exists on the map but has not been priced yet. Publishing
 * is refused while any tier in use is unpriced (requirements/003 criterion 3), so the null is
 * a precondition rather than a defect.
 *
 * <p>Changing the amount later is allowed and applies only to sales made afterwards
 * (criterion 10). Nothing is versioned here to achieve that: a Ticket and a Seat Hold capture
 * their price when they are created, so a later edit cannot reach back to them.
 */
@Entity
@Table(name = "event_pricing_tier")
public class PricingTier {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String name;

    private Long amount;

    @Column(nullable = false)
    private String currency = Money.Currency.VND.name();

    protected PricingTier() {}

    public PricingTier(UUID organizationId, UUID eventId, String name) {
        this.organizationId = organizationId;
        this.eventId = eventId;
        this.name = name;
    }

    public UUID eventId() {
        return eventId;
    }

    public String name() {
        return name;
    }

    public boolean isPriced() {
        return amount != null;
    }

    /** The price, or null while the tier is unpriced. */
    public Money price() {
        return amount == null ? null : new Money(amount, Money.Currency.valueOf(currency));
    }

    public void priceAt(Money price) {
        this.amount = price.amount();
        this.currency = price.currency().name();
    }
}
