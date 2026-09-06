package com.eventticket.checkout.web;

import com.eventticket.api.model.Money;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.OrderSeat;
import com.eventticket.api.model.OrderStatus;
import com.eventticket.checkout.domain.OrderDetail;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * One Order shape, two controllers.
 *
 * <p>A buyer reads their own Orders through {@code CheckoutController} and an organizer reads
 * an Event's through {@code RefundController}, and both send the contract's {@code Order}. Two
 * copies of this mapping would be two places for a field to be forgotten - which is exactly
 * what {@code refundRequired} is: a flag that means nothing until somebody can see it.
 *
 * <p>{@code Order}, {@code OrderSeat} and {@code Money} all exist in both the contract and the
 * domain. The generated ones are imported and the domain ones qualified; getting that backwards
 * compiles and then maps the wrong type.
 */
final class OrderMapper {

    private OrderMapper() {}

    static Order toDto(OrderDetail detail) {
        var order = detail.order();
        var dto = new Order(order.id(), order.eventId(),
                OrderStatus.fromValue(order.status().name()), toDto(order.total()));
        dto.setEventTitle(detail.eventTitle());
        dto.setHoldExpiresAt(at(order.holdExpiresAt()));
        dto.setCreatedAt(at(order.createdAt()));
        dto.setRefundRequired(order.refundRequired());
        // Null on a buyer's own Order, where it would be their own address, and filled on the
        // organizer's list, where a refund is about somebody they may have to answer to.
        dto.setBuyerEmail(detail.buyerEmail());
        detail.seats().forEach(seat -> dto.addSeatsItem(toDto(seat)));
        return dto;
    }

    static OrderSeat toDto(com.eventticket.checkout.domain.OrderSeat seat) {
        var dto = new OrderSeat();
        dto.setSeatId(seat.eventSeatId());
        dto.setLabel(seat.label());
        dto.setTierName(seat.tierName());
        dto.setPrice(toDto(seat.price()));
        return dto;
    }

    static Money toDto(com.eventticket.shared.money.Money money) {
        return new Money(money.amount(), Money.CurrencyEnum.fromValue(money.currency().name()));
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
