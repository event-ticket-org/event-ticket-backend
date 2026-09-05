package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.domain.OrderDetail;
import com.eventticket.checkout.domain.OrderSeat;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.checkout.repository.OrderSeatRepository;
import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.support.PageCursor;
import com.eventticket.shared.page.Paged;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/006 criterion 5: everything this person has bought, from every Organization,
 * in one place.
 *
 * <p>The caller usually has no active Organization here - they are a buyer looking at their
 * own tickets, not a member of anything. The listing is scoped by buyer, and the policy's
 * buyer branch is what lets it return rows at all.
 */
@Component
public class ListOrders {

    private final OrderRepository orders;
    private final OrderSeatRepository orderSeats;
    private final EventRepository events;

    public ListOrders(OrderRepository orders, OrderSeatRepository orderSeats, EventRepository events) {
        this.orders = orders;
        this.orderSeats = orderSeats;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Paged<OrderDetail> list(int limit, String cursor) {
        PageCursor from = PageCursor.decode(cursor, PageCursor.FIRST_DESCENDING);

        List<Order> page = orders.findPageForBuyer(TenantContext.requireUserId(),
                from.at(), from.id(), PageRequest.ofSize(limit + 1));

        boolean more = page.size() > limit;
        List<Order> visible = more ? page.subList(0, limit) : page;
        if (visible.isEmpty()) {
            return Paged.lastPage(List.of());
        }

        Map<UUID, List<OrderSeat>> seatsByOrder = orderSeats
                .findByOrderIdIn(visible.stream().map(Order::id).toList())
                .stream().collect(Collectors.groupingBy(OrderSeat::orderId));

        Map<UUID, String> titles = events
                .findAllById(visible.stream().map(Order::eventId).distinct().toList())
                .stream().collect(Collectors.toMap(Event::id, Event::title));

        List<OrderDetail> items = visible.stream()
                .map(order -> new OrderDetail(order, titles.get(order.eventId()),
                        seatsByOrder.getOrDefault(order.id(), List.of())))
                .toList();

        Order last = visible.get(visible.size() - 1);
        return new Paged<>(items, more ? PageCursor.encode(last.createdAt(), last.id()) : null);
    }
}
