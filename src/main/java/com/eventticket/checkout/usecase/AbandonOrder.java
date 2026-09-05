package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.repository.EventSeatAvailability;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/004 criterion 11. Walking away releases the seats at once rather than leaving
 * them dead for the rest of the ten minutes - the difference between a row someone else can
 * buy now and one they can buy in nine minutes.
 *
 * <p>Only the buyer may abandon. An Organization can see the Order but cannot cancel someone
 * else's purchase in progress; that is a refund, and refunds are requirements/008.
 */
@Component
public class AbandonOrder {

    private static final Logger log = LoggerFactory.getLogger(AbandonOrder.class);

    private final OrderRepository orders;
    private final EventSeatAvailability availability;

    public AbandonOrder(OrderRepository orders, EventSeatAvailability availability) {
        this.orders = orders;
        this.availability = availability;
    }

    @Transactional
    public void abandon(UUID orderId) {
        UUID userId = TenantContext.requireUserId();
        Order order = orders.findOrThrow(orderId);
        if (!order.buyerUserId().equals(userId)) {
            throw ApiException.notFound("Order");
        }

        order.cancel();
        orders.save(order);
        int released = availability.release(orderId);

        log.info("Abandoned order orderId={} seatsReleased={}", orderId, released);
    }
}
