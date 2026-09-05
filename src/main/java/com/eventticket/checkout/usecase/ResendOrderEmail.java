package com.eventticket.checkout.usecase;

import com.eventticket.event.repository.EventRepository;
import com.eventticket.shared.UserDirectory;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.ticket.domain.Ticket;
import com.eventticket.ticket.repository.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/006 criterion 9. The buyer sends it to themselves, at their own recorded
 * address - the address never comes from the request, which would make this an open relay for
 * anyone who could guess an order id.
 *
 * <p>That there are Tickets at all is the proof the Order was paid; nothing else needs asking.
 */
@Component
public class ResendOrderEmail {

    private static final Logger log = LoggerFactory.getLogger(ResendOrderEmail.class);

    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final EventRepository events;
    private final EmailSender email;
    private final UserDirectory users;
    private final String baseUrl;

    public ResendOrderEmail(TicketRepository tickets, OrderRepository orders, EventRepository events,
                     EmailSender email, UserDirectory users,
                     @Value("${app.base-url}") String baseUrl) {
        this.tickets = tickets;
        this.orders = orders;
        this.events = events;
        this.email = email;
        this.users = users;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public void resend(UUID orderId) {
        UUID userId = TenantContext.requireUserId();
        if (!orders.findOrThrow(orderId).buyerUserId().equals(userId)) {
            throw ApiException.notFound("Order");
        }

        List<Ticket> found = tickets.findByOrderIdOrderBySeatLabelAsc(orderId);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "There are no tickets to send for this order yet.");
        }

        String title = events.findById(found.get(0).eventId()).map(event -> event.title())
                .orElse("your event");
        email.send(users.emailOf(userId),
                "Your tickets for " + title,
                """
                Here are your %d ticket(s) for %s again.

                Open your tickets here: %s/orders/%s
                """.formatted(found.size(), title, baseUrl, orderId));

        log.info("Re-sent order email orderId={} tickets={}", orderId, found.size());
    }
}
