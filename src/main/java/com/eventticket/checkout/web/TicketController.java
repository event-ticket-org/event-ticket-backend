package com.eventticket.checkout.web;

import com.eventticket.api.TicketsApi;
import com.eventticket.api.model.Ticket;
import com.eventticket.ticket.domain.TicketView;
import com.eventticket.checkout.usecase.ListOrderTickets;
import com.eventticket.checkout.usecase.ResendOrderEmail;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ticket page's data, and the only place a Ticket Code leaves the server.
 *
 * <p>It is put into a response body and nowhere else: not into a log line, not into an error
 * message, not into a URL where it would land in an access log (nfr.md).
 */
@RestController
public class TicketController implements TicketsApi {

    private final ListOrderTickets listOrderTickets;
    private final ResendOrderEmail resendOrderEmail;

    public TicketController(ListOrderTickets listOrderTickets, ResendOrderEmail resendOrderEmail) {
        this.listOrderTickets = listOrderTickets;
        this.resendOrderEmail = resendOrderEmail;
    }

    @Override
    public ResponseEntity<List<Ticket>> ordersOrderIdTicketsGet(UUID orderId) {
        return ResponseEntity.ok(listOrderTickets.list(orderId).stream()
                .map(TicketController::toDto).toList());
    }

    @Override
    public ResponseEntity<Void> ordersOrderIdResendEmailPost(UUID orderId) {
        resendOrderEmail.resend(orderId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static Ticket toDto(TicketView view) {
        var ticket = view.ticket();
        var dto = new Ticket(ticket.id(), ticket.orderId(), ticket.eventId(),
                Ticket.StatusEnum.fromValue(ticket.status().name()));
        dto.setSeatLabel(ticket.seatLabel());
        dto.setTierName(ticket.tierName());
        dto.setTicketCode(view.code());
        dto.setRedeemedAt(ticket.redeemedAt() == null
                ? null : ticket.redeemedAt().atOffset(ZoneOffset.UTC));
        return dto;
    }
}
