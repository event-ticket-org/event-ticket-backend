package com.eventticket.ticket.usecase;

import com.eventticket.event.repository.EventRepository;
import com.eventticket.shared.UserDirectory;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.ticket.domain.Ticket;
import com.eventticket.ticket.repository.TicketRepository;
import com.eventticket.ticket.support.TicketCodes;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * KB invariant 20 and requirements/005 criterion 8: Tickets exist only after a payment is
 * confirmed, and every seat of an Order becomes a Ticket together or none does.
 *
 * <p>{@code MANDATORY} rather than {@code REQUIRED}, deliberately. The atomicity is the
 * caller's transaction - the one that also sold the seats and marked the Order paid - so
 * being called outside one is a programming error worth failing on rather than a transaction
 * worth starting.
 *
 * <p>Takes the seats to issue for rather than an Order to read them from, which is why this
 * module does not depend on {@code checkout}.
 */
@Component
public class IssueTickets {

    private static final Logger log = LoggerFactory.getLogger(IssueTickets.class);

    /** @param seats one per Ticket, already priced and paid for by the caller's Order */
    public record Request(UUID organizationId, UUID buyerUserId, UUID orderId, UUID eventId,
                          List<Seat> seats) {

        public record Seat(UUID eventSeatId, String label, String tierName) {}
    }

    private final TicketRepository tickets;
    private final EventRepository events;
    private final TicketCodes codes;
    private final EmailSender email;
    private final UserDirectory users;
    private final String baseUrl;

    public IssueTickets(TicketRepository tickets, EventRepository events, TicketCodes codes,
                 EmailSender email, UserDirectory users, @Value("${app.base-url}") String baseUrl) {
        this.tickets = tickets;
        this.events = events;
        this.codes = codes;
        this.email = email;
        this.users = users;
        this.baseUrl = baseUrl;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Ticket> issue(Request request) {
        List<Ticket> issued = tickets.saveAll(request.seats().stream().map(seat -> {
            TicketCodes.Issued code = codes.issue();
            return new Ticket(request.organizationId(), request.buyerUserId(), request.orderId(),
                    request.eventId(), seat.eventSeatId(), seat.label(), seat.tierName(),
                    code.lookup(), code.version());
        }).toList());

        sendConfirmation(request, issued.size());

        // The count, never the codes. nfr.md: a Ticket Code is never written to a log.
        log.info("Issued tickets orderId={} tickets={}", request.orderId(), issued.size());
        return issued;
    }

    /**
     * requirements/006 criterion 1. A link to the ticket page rather than attached images, so
     * that the QR shown is always the current one - an attachment lives in an inbox forever and
     * cannot be withdrawn.
     *
     * <p>Queued inside the caller's transaction, so an Order that commits has the email owed
     * with it. A confirmed Order that committed with no email row is a buyer who paid and heard
     * nothing, which requirements/006 names as the failure this system exists to prevent.
     */
    private void sendConfirmation(Request request, int ticketCount) {
        String title = events.findById(request.eventId()).map(event -> event.title()).orElse("your event");
        email.send(users.emailOf(request.buyerUserId()),
                "Your tickets for " + title,
                """
                Your payment is confirmed and %d ticket(s) are ready for %s.

                Open your tickets here: %s/orders/%s

                Show the QR code on that page at the door. It is always the current one, so
                keep the link rather than a screenshot.
                """.formatted(ticketCount, title, baseUrl, request.orderId()));
    }
}
