/**
 * Payment Sessions and provider integrations. See KB ADR-0002.
 *
 * <p>There is deliberately no {@code charge()} anywhere in here, and a reader looking for one
 * will not find it. Providers in this market differ in flow rather than in credentials, so a
 * session has a lifecycle and returns a "next action" the client renders without knowing which
 * provider produced it. Payment is never synchronous, and the buyer returning to the site is
 * never what confirms an Order - the provider's webhook is.
 *
 * <p>This module knows nothing about Orders, Tickets or seats. It is the provider abstraction
 * and the record of attempts made against it, and that is all - which is what lets
 * {@code checkout} own the transaction where a confirmation sells seats, marks an Order paid
 * and issues Tickets together. Those three things happen at once or not at all, and a module
 * boundary drawn through the middle of them would have to be crossed in both directions.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared"})
package com.eventticket.payment;
