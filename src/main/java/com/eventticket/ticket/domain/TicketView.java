package com.eventticket.ticket.domain;

/**
 * A Ticket with its current code assembled.
 *
 * <p>A separate type because the code is not a property of the stored Ticket - it is computed
 * from what is stored plus a key, on demand, and never held anywhere. Making that a distinct
 * value rather than a getter on the entity keeps it from being persisted, logged or cached by
 * accident.
 */
public record TicketView(Ticket ticket, String code) {
}
