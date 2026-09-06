package com.eventticket.checkout.domain;

import com.eventticket.event.domain.Event;
import com.eventticket.payment.domain.Refund;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How one cancellation is going, per Order (requirements/008 criterion 7).
 *
 * <p>Derived rather than stored. The refund rows already are the record of the work, and a
 * second table tracking the same thing is a second thing that can disagree with the first -
 * and it would, exactly when somebody is reading it to find out which Orders still need doing
 * by hand.
 *
 * <p>An Order with no refund row at all is {@code REFUND_PENDING} here. That is the honest
 * reading: the cancellation has taken responsibility for it and nothing has happened yet,
 * which is a different thing from a refund the provider refused.
 */
public record EventCancellation(UUID eventId, String reason, Instant startedAt,
                                int pending, int refunded, int failed, List<OrderState> orders) {

    public record OrderState(UUID orderId, Refund.Status status, String failureReason) {}

    public static EventCancellation of(Event event, List<UUID> orderIds, Map<UUID, Refund> byOrder) {
        List<OrderState> states = orderIds.stream()
                .map(orderId -> {
                    Refund refund = byOrder.get(orderId);
                    return refund == null
                            ? new OrderState(orderId, Refund.Status.REFUND_PENDING, null)
                            : new OrderState(orderId, refund.status(), refund.failureReason());
                })
                .toList();

        return new EventCancellation(event.id(), event.cancelReason(), event.cancelledAt(),
                count(states, Refund.Status.REFUND_PENDING),
                count(states, Refund.Status.REFUNDED),
                count(states, Refund.Status.REFUND_FAILED),
                states);
    }

    private static int count(List<OrderState> states, Refund.Status status) {
        return (int) states.stream().filter(state -> state.status() == status).count();
    }
}
