package com.eventticket.venue.web;

import com.eventticket.api.model.MapElement;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.SeatMapSeat;
import com.eventticket.venue.domain.MapSeat;
import com.eventticket.venue.domain.SeatMapDocument;
import java.math.BigDecimal;

/**
 * Seat map shapes, between the contract and the domain.
 *
 * <p>Public because an Event's frozen map carries the same {@code MapElement} furniture, and
 * one mapping for one shape is worth more than keeping the two web layers from knowing about
 * each other - {@code event} already depends on {@code venue}, so nothing new is coupled.
 *
 * <p>Positions cross as {@code BigDecimal}: the contract types them as {@code number} with no
 * format, so that is what the generated models use.
 */
public final class SeatMapMapper {

    private SeatMapMapper() {}

    public static SeatMap toDto(SeatMapDocument map) {
        return new SeatMap(
                map.seats().stream().map(SeatMapMapper::toDto).toList(),
                map.elements().stream().map(SeatMapMapper::toDto).toList());
    }

    public static MapElement toDto(com.eventticket.venue.domain.MapElement element) {
        var dto = new MapElement(MapElement.KindEnum.fromValue(element.kind().name()),
                BigDecimal.valueOf(element.x()), BigDecimal.valueOf(element.y()));
        dto.setLabel(element.label());
        dto.setWidth(element.width() == null ? null : BigDecimal.valueOf(element.width()));
        dto.setHeight(element.height() == null ? null : BigDecimal.valueOf(element.height()));
        return dto;
    }

    public static SeatMapDocument toDocument(SeatMap request) {
        return new SeatMapDocument(
                request.getSeats().stream()
                        .map(seat -> new MapSeat(seat.getLabel(), seat.getX().doubleValue(),
                                seat.getY().doubleValue(), seat.getTierName()))
                        .toList(),
                request.getElements().stream().map(SeatMapMapper::toElement).toList());
    }

    private static SeatMapSeat toDto(MapSeat seat) {
        return new SeatMapSeat(seat.label(), BigDecimal.valueOf(seat.x()),
                BigDecimal.valueOf(seat.y()), seat.tierName());
    }

    private static com.eventticket.venue.domain.MapElement toElement(MapElement element) {
        return new com.eventticket.venue.domain.MapElement(
                com.eventticket.venue.domain.MapElement.Kind.valueOf(element.getKind().getValue()),
                element.getLabel(),
                element.getX().doubleValue(),
                element.getY().doubleValue(),
                element.getWidth() == null ? null : element.getWidth().doubleValue(),
                element.getHeight() == null ? null : element.getHeight().doubleValue());
    }
}
