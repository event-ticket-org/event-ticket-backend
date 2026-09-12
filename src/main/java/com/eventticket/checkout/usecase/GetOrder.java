package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.domain.OrderDetail;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Readable by the buyer, and by the Organization selling the event. Both need it - the buyer
 * to see what they bought, the Organization to answer a question about it at the door - and
 * the policy admits exactly those two.
 */
@Component
public class GetOrder {

    private final OrderRepository orders;
    private final EventRepository events;

    public GetOrder(OrderRepository orders, EventRepository events) {
        this.orders = orders;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public OrderDetail get(UUID orderId) {
        Order order = orders.findOrThrow(orderId)
                .requireBuyerOrOrganization(TenantContext.requireUserId(), TenantContext.organizationId());

        // The seats arrived with the Order. The second query this used to make is not
        // optimised away - it no longer exists, which is what embedding buys.
        return new OrderDetail(order, events.findOrThrow(order.eventId()).title(), order.seats());
    }
}
