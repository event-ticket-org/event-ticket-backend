package com.eventticket.event.domain;

import com.eventticket.shared.money.Money;
import com.eventticket.venue.domain.SeatMapDocument;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Pricing Tiers an Event actually has, priced or not.
 *
 * <p>Two sources have to be reconciled to answer that: the tier names in use, which come from
 * a Seat Map, and the prices, which are rows. A tier can be named on the map with no row yet,
 * and a row can survive a map edit that removed the last seat using it. Neither source is
 * wrong, so the merge is a domain value rather than a query - and it lives here rather than
 * in a helper between use cases, because {@code GetEvent}, {@code SetPricingTiers} and
 * {@code PublishEvent} all need the same answer (ADR-0001: shared logic goes down).
 */
public record EventPricing(List<Tier> tiers) {

    /** A price of null means the tier is on the map but has not been priced. */
    public record Tier(String name, Money price) {}

    /**
     * Where the names in use come from depends on where the Event is in its life. A Draft
     * tracks its Venue's map and its tiers change as the map does; a published Event took its
     * copy at publish, and {@code PublishEvent} left exactly one row per tier in use, so from
     * then on the rows are the answer.
     *
     * @param draftMap the Venue's Seat Map, needed only while the Event is a Draft
     */
    public static EventPricing of(Event event, SeatMapDocument draftMap, Collection<PricingTier> rows) {
        Collection<String> namesInUse = event.isPublished()
                ? rows.stream().map(PricingTier::name).toList()
                : draftMap.tierNames();
        return of(namesInUse, rows);
    }

    public static EventPricing of(Collection<String> namesInUse, Collection<PricingTier> rows) {
        Map<String, Money> priced = new LinkedHashMap<>();
        rows.forEach(row -> priced.put(row.name(), row.price()));

        return new EventPricing(namesInUse.stream()
                .map(name -> new Tier(name, priced.get(name)))
                .toList());
    }

    /** requirements/003 criterion 3: publishing is refused while any of these is non-empty. */
    public List<String> unpricedNames() {
        return tiers.stream().filter(t -> t.price() == null).map(Tier::name).toList();
    }

    /** The "from" price on a public listing (requirements/009). */
    public Optional<Money> cheapest() {
        return tiers.stream()
                .map(Tier::price)
                .filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.comparingLong(Money::amount));
    }
}
