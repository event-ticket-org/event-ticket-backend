package com.eventticket.event.web;

import com.eventticket.api.model.EventSeatMap;
import com.eventticket.api.model.EventStatus;
import com.eventticket.api.model.Money;
import com.eventticket.api.model.PricingTier;
import com.eventticket.api.model.PublicEvent;
import com.eventticket.api.model.PublicEventSummary;
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.domain.EventSeat;
import com.eventticket.event.domain.EventSeatMapView;
import com.eventticket.event.domain.PublicEventView;
import com.eventticket.venue.web.SeatMapMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Contract shapes, in one place, so that neither Event controller grows a second version. */
final class EventMapper {

    private EventMapper() {}

    static com.eventticket.api.model.Event toDto(EventDetail detail) {
        Event event = detail.event();
        var dto = new com.eventticket.api.model.Event(event.title(), event.venueId(),
                at(event.startsAt()), event.id(), event.organizationId(),
                EventStatus.fromValue(event.status().name()));
        dto.setDescription(event.description());
        dto.setCoverImageUrl(uri(event.coverImageUrl()));
        dto.setListed(event.isListed());
        dto.setPublishedAt(at(event.publishedAt()));
        dto.setPricingTiers(toDto(detail.pricing()));
        // Nothing has been sold until Orders exist (requirements/004-005). A real count
        // replaces this when they do; it is not a placeholder for an unwritten query.
        dto.setSoldCount(0);
        dto.setNotifyCount(detail.notifiedCount());
        return dto;
    }

    static PublicEvent toPublicDto(PublicEventView view) {
        Event event = view.event();
        var dto = new PublicEvent(event.id(), event.title(), view.organizationName(),
                view.venueName(), view.city(), at(event.startsAt()), view.timezone());
        dto.setCoverImageUrl(uri(event.coverImageUrl()));
        dto.setDescription(event.description());
        dto.setStatus(EventStatus.fromValue(event.status().name()));
        dto.setPricingTiers(toDto(view.pricing()));
        view.pricing().cheapest().ifPresent(price -> dto.setPriceFrom(toDto(price)));
        return dto;
    }

    static PublicEventSummary toSummaryDto(PublicEventView view) {
        Event event = view.event();
        var dto = new PublicEventSummary(event.id(), event.title(), view.organizationName(),
                view.venueName(), view.city(), at(event.startsAt()), view.timezone());
        dto.setCoverImageUrl(uri(event.coverImageUrl()));
        view.pricing().cheapest().ifPresent(price -> dto.setPriceFrom(toDto(price)));
        return dto;
    }

    static EventSeatMap toDto(EventSeatMapView map) {
        return new EventSeatMap(
                map.seats().stream().map(EventMapper::toDto).toList(),
                map.elements().stream().map(SeatMapMapper::toDto).toList());
    }

    static List<PricingTier> toDto(EventPricing pricing) {
        return pricing.tiers().stream()
                .map(tier -> new PricingTier(tier.name(),
                        tier.price() == null ? null : toDto(tier.price())))
                .toList();
    }

    static com.eventticket.shared.money.Money toMoney(Money price) {
        return new com.eventticket.shared.money.Money(price.getAmount(),
                com.eventticket.shared.money.Money.Currency.valueOf(price.getCurrency().getValue()));
    }

    private static com.eventticket.api.model.EventSeat toDto(EventSeat seat) {
        return new com.eventticket.api.model.EventSeat(seat.id(), seat.label(),
                BigDecimal.valueOf(seat.x()), BigDecimal.valueOf(seat.y()), seat.tierName(),
                SeatAvailability.fromValue(seat.availability().name()));
    }

    static Money toDto(com.eventticket.shared.money.Money price) {
        return new Money(price.amount(), Money.CurrencyEnum.fromValue(price.currency().name()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static URI uri(String value) {
        return value == null ? null : URI.create(value);
    }
}
