package com.eventticket.checkout.usecase;

import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.ticket.domain.Ticket;
import com.eventticket.ticket.domain.TicketView;
import com.eventticket.ticket.repository.TicketRepository;
import com.eventticket.ticket.support.TicketCodes;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/006 criteria 2, 3 and 6. The ticket page, behind the buyer's login.
 *
 * <p>Each code is assembled on the way out rather than read from a column, which is what makes
 * criterion 6 free: reissuing changes the stored lookup, and the next time this page is opened
 * it shows the new code without the buyer doing anything.
 *
 * <p>Authorised against the Order, not against the Tickets it returns. Row-level security
 * already hides another buyer's Tickets, so checking them would answer an empty list for
 * "not yours" and for "not paid for yet" alike - and one of those is a 404. Gate Staff reach
 * Tickets through the scanner, never through this.
 */
@Component
public class ListOrderTickets {

    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final TicketCodes codes;

    public ListOrderTickets(TicketRepository tickets, OrderRepository orders, TicketCodes codes) {
        this.tickets = tickets;
        this.orders = orders;
        this.codes = codes;
    }

    @Transactional(readOnly = true)
    public List<TicketView> list(UUID orderId) {
        UUID userId = TenantContext.requireUserId();
        if (!orders.findOrThrow(orderId).buyerUserId().equals(userId)) {
            throw com.eventticket.shared.error.ApiException.notFound("Order");
        }

        List<Ticket> found = tickets.findByOrderIdOrderBySeatLabelAsc(orderId);
        return found.stream()
                .map(ticket -> new TicketView(ticket, codes.format(ticket.codeLookup(), ticket.codeVersion())))
                .toList();
    }
}
