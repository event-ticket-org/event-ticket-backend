/**
 * Scanning and Redemption at the door.
 *
 * <p>Verification is online only (nfr.md). The server is the sole authority on whether a Ticket
 * has been used, which makes double admission across simultaneous devices impossible by
 * construction rather than by reconciliation afterwards - and is why there is nothing here that
 * caches, pre-fetches or optimistically admits.
 *
 * <p>Every scan is recorded, refusals included. A refused scan is the only record that somebody
 * stood at a door with a ticket that did not work, and "the system said no" is not an answer
 * anyone can act on later without it.
 *
 * <p>One class per use case, named after what the user does. No AdmissionService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization", "event", "venue", "ticket"})
package com.eventticket.admission;
