/**
 * Seat Holds, Orders, and the transaction in which an Order becomes paid Tickets.
 *
 * <p>A Seat Hold is not an entity. It is three columns on an Event Seat, written by the SQL
 * functions in {@code V5__checkout_payment_and_tickets.sql}, so that a second hold on a seat
 * has nowhere to exist. KB invariant 5 asks that the database win the race rather than
 * application code, and at 500 concurrent buyers the race is the normal case.
 *
 * <p>Confirming a payment sells the seats, marks the Order paid and issues its Tickets in one
 * transaction (requirements/005 criterion 8), so this module owns that step and depends on
 * {@code payment} and {@code ticket} rather than being called by them. Splitting it would put
 * a module boundary through the middle of a single transaction and force it to be crossed in
 * both directions.
 *
 * <p>It also owns refunds and Event cancellation (requirements/008), for the same reason and
 * more so: refunding voids Tickets, puts seats back on sale, reverses a charge and marks an
 * Order, which is four modules in one decision. Cancelling an Event reads as an {@code event}
 * operation and is not one - it is many refunds, and {@code event} cannot see Orders.
 *
 * <p>The dependency on {@code organization} is the authorisation check refunding needs:
 * requirements/008 criterion 1 puts it in the hands of an Owner or Manager, and {@code
 * Managers} is where that question is answered - the same way {@code admission} asks who may
 * scan.
 *
 * <p>One class per use case, named after what the user does. No CheckoutService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization", "event", "venue", "payment", "ticket"})
package com.eventticket.checkout;
