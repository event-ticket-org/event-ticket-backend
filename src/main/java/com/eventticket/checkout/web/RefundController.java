package com.eventticket.checkout.web;

import com.eventticket.api.RefundsApi;
import com.eventticket.api.model.EventCancellation;
import com.eventticket.api.model.OrderPage;
import com.eventticket.api.model.OrderRefundState;
import com.eventticket.api.model.OrderStatus;
import com.eventticket.api.model.Refund;
import com.eventticket.api.model.RefundRequest;
import com.eventticket.api.model.RefundStatus;
import com.eventticket.checkout.usecase.CancelEvent;
import com.eventticket.checkout.usecase.ListEventOrders;
import com.eventticket.checkout.usecase.ListOrderRefunds;
import com.eventticket.checkout.usecase.RefundOrder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refunds, cancellation, and the organizer's view of what an Event sold.
 *
 * <p>In {@code checkout} rather than {@code event}, because the generated interface groups by
 * OpenAPI tag and the tag is right: cancelling an Event is many refunds, and the module that
 * can see Orders is the one that can implement it.
 *
 * <p>Both write endpoints answer 202 rather than 200. A refund has been started and not
 * finished - the provider says when the money moved - and a cancellation is a list of Orders
 * that are still being worked through. Answering 200 would say both were done.
 */
@RestController
public class RefundController implements RefundsApi {

    private final RefundOrder refundOrder;
    private final ListOrderRefunds listOrderRefunds;
    private final ListEventOrders listEventOrders;
    private final CancelEvent cancelEvent;

    public RefundController(RefundOrder refundOrder, ListOrderRefunds listOrderRefunds,
                     ListEventOrders listEventOrders, CancelEvent cancelEvent) {
        this.refundOrder = refundOrder;
        this.listOrderRefunds = listOrderRefunds;
        this.listEventOrders = listEventOrders;
        this.cancelEvent = cancelEvent;
    }

    @Override
    public ResponseEntity<Refund> ordersOrderIdRefundsPost(UUID orderId, RefundRequest request) {
        var refund = refundOrder.refund(orderId, request.getReason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(refund));
    }

    @Override
    public ResponseEntity<List<Refund>> ordersOrderIdRefundsGet(UUID orderId) {
        return ResponseEntity.ok(listOrderRefunds.list(orderId).stream()
                .map(RefundController::toDto).toList());
    }

    @Override
    public ResponseEntity<OrderPage> eventsEventIdOrdersGet(UUID eventId, OrderStatus status,
                                                            Boolean refundRequired,
                                                            Integer limit, String cursor) {
        var page = listEventOrders.list(eventId, toDomain(status), refundRequired, limit, cursor);
        var dto = new OrderPage();
        page.items().forEach(detail -> dto.addItemsItem(OrderMapper.toDto(detail)));
        dto.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<EventCancellation> eventsEventIdCancelPost(UUID eventId,
                                                                     RefundRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(toDto(cancelEvent.cancel(eventId, request.getReason())));
    }

    @Override
    public ResponseEntity<EventCancellation> eventsEventIdCancelGet(UUID eventId) {
        return ResponseEntity.ok(toDto(cancelEvent.progress(eventId)));
    }

    private static com.eventticket.checkout.domain.Order.Status toDomain(OrderStatus status) {
        return status == null
                ? null : com.eventticket.checkout.domain.Order.Status.valueOf(status.getValue());
    }

    private static Refund toDto(com.eventticket.payment.domain.Refund refund) {
        var dto = new Refund(refund.id(), refund.orderId(), OrderMapper.toDto(refund.amount()),
                RefundStatus.fromValue(refund.status().name()));
        dto.setReason(refund.reason());
        dto.setFailureReason(refund.failureReason());
        dto.setCreatedAt(at(refund.createdAt()));
        dto.setSettledAt(at(refund.settledAt()));
        return dto;
    }

    private static EventCancellation toDto(
            com.eventticket.checkout.domain.EventCancellation cancellation) {
        var dto = new EventCancellation(cancellation.eventId(), at(cancellation.startedAt()),
                cancellation.orders().stream().map(RefundController::toDto).toList());
        dto.setReason(cancellation.reason());
        dto.setPending(cancellation.pending());
        dto.setRefunded(cancellation.refunded());
        dto.setFailed(cancellation.failed());
        return dto;
    }

    private static OrderRefundState toDto(
            com.eventticket.checkout.domain.EventCancellation.OrderState state) {
        var dto = new OrderRefundState(state.orderId(), RefundStatus.fromValue(state.status().name()));
        dto.setFailureReason(state.failureReason());
        return dto;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
