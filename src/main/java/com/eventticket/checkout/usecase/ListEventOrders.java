package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.domain.OrderDetail;
import com.eventticket.checkout.domain.OrderSeat;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.checkout.repository.OrderSeatRepository;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.UserDirectory;
import com.eventticket.event.support.PageCursor;
import com.eventticket.shared.page.Paged;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organizer's view of an Event's Orders (requirements/008 criterion 10).
 *
 * <p>{@link ListOrders} is the buyer's own list and answers a different question - everything
 * one person has bought, from every Organization. This one is everything one Event sold, to
 * everybody, and it exists because criterion 1 puts refunding in the hands of an Owner or
 * Manager and an Owner who cannot find an Order cannot refund one.
 *
 * <p>{@code refundRequired=true} is the narrow, urgent case: Orders holding money for seats
 * that were never delivered.
 */
@Component
public class ListEventOrders {

    private final OrderRepository orders;
    private final OrderSeatRepository orderSeats;
    private final EventRepository events;
    private final Managers managers;
    private final UserDirectory users;

    public ListEventOrders(OrderRepository orders, OrderSeatRepository orderSeats,
                    EventRepository events, Managers managers, UserDirectory users) {
        this.orders = orders;
        this.orderSeats = orderSeats;
        this.events = events;
        this.managers = managers;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Paged<OrderDetail> list(UUID eventId, Order.Status status, Boolean refundRequired,
                                   int limit, String cursor) {
        UUID organizationId = TenantContext.requireOrganizationId();
        // The same line the rest of the manager surface draws: Gate Staff see no sales.
        managers.requireCallerCanManageEvents(organizationId);

        var event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        PageCursor from = PageCursor.decode(cursor, PageCursor.FIRST_DESCENDING);

        // Absent filters are the set of everything, not a null the query has to test for.
        Collection<Order.Status> statuses = status == null
                ? EnumSet.allOf(Order.Status.class) : EnumSet.of(status);
        Collection<Boolean> flags = refundRequired == null
                ? Set.of(true, false) : Set.of(refundRequired);

        List<Order> page = orders.findPageForEvent(event.id(), statuses, flags,
                from.at(), from.id(), PageRequest.ofSize(limit + 1));

        boolean more = page.size() > limit;
        List<Order> visible = more ? page.subList(0, limit) : page;
        if (visible.isEmpty()) {
            return Paged.lastPage(List.of());
        }

        // Seats and addresses fetched once for the page. A refund list is read row by row by a
        // person deciding something, so the shape that turns twenty rows into forty queries is
        // the one to avoid before it exists.
        List<UUID> orderIds = visible.stream().map(Order::id).toList();
        Map<UUID, List<OrderSeat>> seatsByOrder = orderSeats.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderSeat::orderId));
        Map<UUID, String> emails = users.emailsOf(
                visible.stream().map(Order::buyerUserId).distinct().toList());

        List<OrderDetail> items = visible.stream()
                .map(order -> new OrderDetail(order, event.title(),
                        seatsByOrder.getOrDefault(order.id(), List.of()),
                        emails.get(order.buyerUserId())))
                .toList();

        Order last = visible.get(visible.size() - 1);
        return new Paged<>(items, more ? PageCursor.encode(last.createdAt(), last.id()) : null);
    }
}
