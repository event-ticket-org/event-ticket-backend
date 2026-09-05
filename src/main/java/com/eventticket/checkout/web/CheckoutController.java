package com.eventticket.checkout.web;

import com.eventticket.api.CheckoutApi;
import com.eventticket.api.model.CheckoutRequest;
import com.eventticket.api.model.Money;
import com.eventticket.api.model.NextAction;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.OrderPage;
import com.eventticket.api.model.OrderSeat;
import com.eventticket.api.model.OrderStatus;
import com.eventticket.api.model.PaymentSession;
import com.eventticket.api.model.StartPaymentRequest;
import com.eventticket.checkout.domain.OrderDetail;
import com.eventticket.checkout.usecase.AbandonOrder;
import com.eventticket.checkout.usecase.BeginCheckout;
import com.eventticket.checkout.usecase.GetOrder;
import com.eventticket.checkout.usecase.ListOrders;
import com.eventticket.checkout.usecase.StartPayment;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code Order}, {@code OrderSeat}, {@code Money} and {@code NextAction} all exist in both the
 * contract and the domain. The generated ones are imported and the domain ones are qualified;
 * getting that backwards compiles and then maps the wrong type.
 */
@RestController
public class CheckoutController implements CheckoutApi {

    private final BeginCheckout beginCheckout;
    private final GetOrder getOrder;
    private final ListOrders listOrders;
    private final AbandonOrder abandonOrder;
    private final StartPayment startPayment;

    public CheckoutController(BeginCheckout beginCheckout, GetOrder getOrder, ListOrders listOrders,
                       AbandonOrder abandonOrder, StartPayment startPayment) {
        this.beginCheckout = beginCheckout;
        this.getOrder = getOrder;
        this.listOrders = listOrders;
        this.abandonOrder = abandonOrder;
        this.startPayment = startPayment;
    }

    @Override
    public ResponseEntity<Order> checkoutPost(CheckoutRequest request) {
        OrderDetail created = beginCheckout.begin(request.getEventId(), request.getSeatIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    @Override
    public ResponseEntity<Order> ordersOrderIdGet(UUID orderId) {
        return ResponseEntity.ok(toDto(getOrder.get(orderId)));
    }

    @Override
    public ResponseEntity<OrderPage> ordersGet(Integer limit, String cursor) {
        var page = listOrders.list(limit, cursor);
        var dto = new OrderPage();
        page.items().forEach(detail -> dto.addItemsItem(toDto(detail)));
        dto.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<Void> ordersOrderIdDelete(UUID orderId) {
        abandonOrder.abandon(orderId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<PaymentSession> ordersOrderIdPaymentSessionsPost(
            UUID orderId, StartPaymentRequest request) {
        var session = startPayment.start(orderId, request.getProvider());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(session));
    }

    private static Order toDto(OrderDetail detail) {
        var order = detail.order();
        var dto = new Order(order.id(), order.eventId(),
                OrderStatus.fromValue(order.status().name()), toDto(order.total()));
        dto.setEventTitle(detail.eventTitle());
        dto.setHoldExpiresAt(at(order.holdExpiresAt()));
        dto.setCreatedAt(at(order.createdAt()));
        detail.seats().forEach(seat -> dto.addSeatsItem(toDto(seat)));
        return dto;
    }

    private static OrderSeat toDto(com.eventticket.checkout.domain.OrderSeat seat) {
        var dto = new OrderSeat();
        dto.setSeatId(seat.eventSeatId());
        dto.setLabel(seat.label());
        dto.setTierName(seat.tierName());
        dto.setPrice(toDto(seat.price()));
        return dto;
    }

    private static PaymentSession toDto(com.eventticket.payment.domain.PaymentSession session) {
        var dto = new PaymentSession(session.id(), session.orderId(),
                PaymentSession.StatusEnum.fromValue(session.status().name()),
                toDto(session.nextAction()));
        dto.setProvider(session.provider());
        dto.setExpiresAt(at(session.expiresAt()));
        return dto;
    }

    private static NextAction toDto(com.eventticket.payment.domain.NextAction action) {
        var dto = new NextAction(NextAction.TypeEnum.fromValue(action.type().name()));
        dto.setUrl(action.url() == null ? null : URI.create(action.url()));
        dto.setQrPayload(action.qrPayload());
        dto.setReference(action.reference());
        return dto;
    }

    private static Money toDto(com.eventticket.shared.money.Money money) {
        return new Money(money.amount(), Money.CurrencyEnum.fromValue(money.currency().name()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
