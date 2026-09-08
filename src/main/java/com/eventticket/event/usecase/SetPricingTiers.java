package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.money.Money;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criteria 3 and 10.
 *
 * <p>After publish a price change is allowed and applies only to later sales. Nothing here
 * needs to arrange that: a Ticket and a Seat Hold capture their price when they are created,
 * so an edit cannot reach back to one that already exists (KB invariant 10).
 */
@Component
public class SetPricingTiers {

    private static final Logger log = LoggerFactory.getLogger(SetPricingTiers.class);

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;
    private final AuditTrail audit;
    private final long minimumAmount;

    public SetPricingTiers(EventRepository events, PricingTierRepository tiers, VenueRepository venues,
                    Managers managers, AuditTrail audit,
                    @Value("${app.pricing.minimum-amount}") long minimumAmount) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
        this.audit = audit;
        this.minimumAmount = minimumAmount;
    }

    /**
     * requirements/003 criterion 3: a price is free, or one somebody can actually be charged.
     *
     * <p>Every provider refuses below a floor of its own - Stripe answers {@code amount_too_small},
     * "must convert to at least 50 cents", which against the dong is around 12.500 ₫ and moves
     * with the exchange rate. A tier under it is not merely cheap, it is unsellable: the
     * smallest possible Order is one seat, and the first person who wants exactly one is stopped
     * by the provider after choosing it.
     *
     * <p>Checked here, where an organizer is setting the number, rather than at checkout where a
     * buyer would meet it. Strictly the provider's floor is on the Order total and 30 ₫ would
     * clear it at five hundred seats - which is an argument for checking the total as well, not
     * for letting somebody publish a price that strands the first single-seat buyer.
     *
     * <p>Zero is not small, it is free, and a provider asked for nothing does not refuse. That
     * was checked against a real Stripe account rather than assumed.
     */
    private void requireChargeable(String name, Money price) {
        if (price == null || price.amount() == 0 || price.amount() >= minimumAmount) {
            return;
        }
        throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                "A ticket priced at " + price.amount() + " " + price.currency()
                        + " cannot be paid for. Use 0 for a free event, or at least "
                        + minimumAmount + " " + price.currency() + ".",
                Map.of("tierName", name, "minimumAmount", minimumAmount));
    }

    @Transactional
    public EventPricing set(UUID eventId, Map<String, Money> prices) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        SeatMapDocument map = event.isPublished() ? null : venues.findOrThrow(event.venueId()).seatMap();

        List<PricingTier> rows = tiers.findByEventId(eventId);
        Map<String, PricingTier> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.name(), row));

        EventPricing existing = EventPricing.of(event, map, rows);
        List<String> namesInUse = existing.tiers().stream().map(EventPricing.Tier::name).toList();

        prices.forEach((name, price) -> {
            if (!namesInUse.contains(name)) {
                // Pricing a tier no seat belongs to is almost always a typo in the tier name,
                // and silently storing it would leave the manager waiting for a price to appear.
                throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                        "No seat belongs to the pricing tier \"" + name + "\".",
                        Map.of("tierName", name));
            }
            requireChargeable(name, price);
            byName.computeIfAbsent(name, n -> new PricingTier(organizationId, eventId, n)).priceAt(price);
        });

        List<PricingTier> saved = tiers.saveAll(byName.values());
        audit.record(organizationId, AuditTrail.EVENT_PRICES_CHANGED, event.title());
        log.info("Priced event eventId={} tiers={}", eventId, prices.keySet());

        return EventPricing.of(event, map, saved);
    }
}
