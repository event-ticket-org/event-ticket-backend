/**
 * Tickets, Ticket Codes and delivery.
 *
 * <p>A Ticket Code is never stored. What is stored is a random lookup value; the code the
 * buyer sees is that value plus a MAC computed with a key held outside the schema, so reading
 * the table is not enough to walk through a door (nfr.md). Reissuing is a new lookup value,
 * which is KB invariant 15 with nothing to revoke.
 *
 * <p>Knows nothing about Orders. Issuing takes the seats it should issue for rather than an
 * Order to read them from, and the order-scoped views of a Ticket - the buyer's ticket page and
 * the resend - live in {@code checkout}, which is the module that can tell "not your order"
 * from "no tickets yet". That is what keeps {@code checkout}, which must call this inside its
 * own transaction, the only one of the two that depends on the other.
 *
 * <p>One class per use case, named after what the user does. No TicketService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "event", "venue"})
package com.eventticket.ticket;
