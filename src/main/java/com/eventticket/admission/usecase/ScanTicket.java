package com.eventticket.admission.usecase;

import com.eventticket.admission.domain.Scan;
import com.eventticket.admission.domain.ScanOutcome;
import com.eventticket.admission.domain.ScanResult;
import com.eventticket.admission.repository.ScanRepository;
import com.eventticket.admission.support.DeviceRateLimiter;
import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.organization.domain.Scanners;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.ticket.domain.Ticket;
import com.eventticket.ticket.repository.TicketRepository;
import com.eventticket.ticket.support.TicketCodes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single admission endpoint (knowledge base requirements/007).
 *
 * <p>There is no override and no force-admit (criterion 7). Not "there is a permission for it
 * that nobody has" - there is no code path that admits a refused Ticket, because the only way
 * to be admitted is to win the conditional update below, and it is conditional on the Ticket
 * being valid.
 *
 * <p>The checks run in the order a person at a door would want them: is this a ticket at all,
 * is it for this event, is this door open, is the ticket still good, and only then does anyone
 * go in. Every one of them ends in a recorded Scan (criterion 6) - a refused attempt is the
 * only trace that somebody stood at a gate with something that did not work.
 */
@Component
public class ScanTicket {

    private static final Logger log = LoggerFactory.getLogger(ScanTicket.class);

    private final EventRepository events;
    private final TicketRepository tickets;
    private final ScanRepository scans;
    private final TicketCodes codes;
    private final Scanners scanners;
    private final DeviceRateLimiter rateLimiter;

    public ScanTicket(EventRepository events, TicketRepository tickets, ScanRepository scans,
               TicketCodes codes, Scanners scanners, DeviceRateLimiter rateLimiter) {
        this.events = events;
        this.tickets = tickets;
        this.scans = scans;
        this.codes = codes;
        this.scanners = scanners;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public ScanResult scan(UUID eventId, String ticketCode, String deviceId) {
        // Before anything is read. A code an attacker generates costs them nothing, and this
        // is what keeps it from costing us a database round trip and a row.
        rateLimiter.requireWithinLimit(deviceId);

        UUID organizationId = TenantContext.requireOrganizationId();
        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        UUID scannerUserId = scanners.requireCallerCanScan(organizationId).userId();

        Instant now = Instant.now();
        Decision decision = decide(event, ticketCode, deviceId, scannerUserId, now);
        ScanResult result = decision.result();

        scans.save(new Scan(organizationId, eventId, decision.ticketId(),
                scannerUserId, deviceId, result.outcome()));

        // The outcome and the seat, never the code. nfr.md: a Ticket Code is never logged, and
        // the scan request body is the easiest place in the system to leak every code presented
        // at a door.
        log.info("Scan eventId={} device={} outcome={} seat={}",
                eventId, deviceId, result.outcome(), result.seatLabel());

        return result;
    }

    /** The outcome, and the Ticket it was about - null when the code resolved to nothing. */
    private record Decision(ScanResult result, UUID ticketId) {

        static Decision refused(Ticket ticket, ScanOutcome outcome, String message) {
            return new Decision(ScanResult.refused(outcome, message, ticket.seatLabel(),
                    ticket.tierName()), ticket.id());
        }
    }

    private Decision decide(Event event, String ticketCode, String deviceId,
                            UUID scannerUserId, Instant now) {
        // The MAC is checked before any lookup, so a forged or mistyped code never reaches the
        // database. That is most of requirements/007 criterion 10's 500 ms budget protected
        // from the one input an attacker controls entirely.
        Optional<Ticket> found = codes.lookupIn(ticketCode).flatMap(tickets::findByCodeLookup);
        if (found.isEmpty()) {
            return new Decision(ScanResult.refused(ScanOutcome.UNKNOWN_CODE,
                    "That code is not a ticket for anything."), null);
        }
        Ticket ticket = found.get();

        if (!ticket.eventId().equals(event.id())) {
            return Decision.refused(ticket, ScanOutcome.WRONG_EVENT,
                    "This ticket is for a different event.");
        }
        if (event.status() == Event.Status.CANCELLED) {
            return Decision.refused(ticket, ScanOutcome.TICKET_VOID, "This event was cancelled.");
        }
        if (!event.doorsAreOpen(now)) {
            return Decision.refused(ticket, ScanOutcome.EVENT_NOT_OPEN, "Doors are not open yet.");
        }
        if (event.hasEnded(now)) {
            return Decision.refused(ticket, ScanOutcome.EVENT_ENDED, "This event has ended.");
        }
        if (ticket.isVoid()) {
            return Decision.refused(ticket, ScanOutcome.TICKET_VOID,
                    "This ticket is no longer valid.");
        }

        return new Decision(redeem(ticket, deviceId, scannerUserId, now), ticket.id());
    }

    /**
     * criterion 9. Two devices, one code, the same instant: the loser of the conditional update
     * blocks on the row lock, finds a status that is no longer VALID, and reads back who got in
     * first. Nothing adjudicates - the row count is the decision.
     */
    private ScanResult redeem(Ticket ticket, String deviceId, UUID scannerUserId, Instant now) {
        int admitted = tickets.redeem(ticket.id(), Ticket.Status.REDEEMED, Ticket.Status.VALID,
                now, scannerUserId, deviceId);

        if (admitted == 1) {
            return new ScanResult(ScanOutcome.ADMITTED, "Admitted",
                    ticket.seatLabel(), ticket.tierName(), null, null);
        }

        // criterion 5. When and where it was first used is the difference between telling
        // someone they have already been in and telling them somebody else used their ticket.
        Ticket redeemed = tickets.findById(ticket.id()).orElse(ticket);
        return new ScanResult(ScanOutcome.ALREADY_REDEEMED,
                "This ticket has already been used.",
                redeemed.seatLabel(), redeemed.tierName(),
                redeemed.redeemedAt(), redeemed.redeemedDeviceId());
    }
}
