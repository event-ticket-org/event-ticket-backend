package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.domain.OrderDetail;
import com.eventticket.checkout.domain.OrderSeat;
import com.eventticket.checkout.repository.OrderRepository;
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
    private final EventRepository events;

    public ListOrders(OrderRepository orders, EventRepository events) {
        this.orders = orders;
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

        // The seats-by-order map is gone. It existed to fetch a page's children in one query
        // and group them in memory - the hand-written application-side join that keeps a
        // listing from becoming N+1. Embedding removed the need for it rather than making it
        // cheaper: the seats arrived inside the Orders. The titles below still need exactly
        // that shape, because an Event is genuinely a separate document.
        Map<UUID, String> titles = events
                .findAllById(visible.stream().map(Order::eventId).distinct().toList())
                .stream().collect(Collectors.toMap(Event::id, Event::title));

        List<OrderDetail> items = visible.stream()
                .map(order -> new OrderDetail(order, titles.get(order.eventId()), order.seats()))
                .toList();

        Order last = visible.get(visible.size() - 1);
        return new Paged<>(items, more ? PageCursor.encode(last.createdAt(), last.id()) : null);
    }
}
