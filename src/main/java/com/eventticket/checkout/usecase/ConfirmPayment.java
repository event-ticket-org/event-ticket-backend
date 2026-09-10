package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.repository.EventSeatAvailability;
import com.eventticket.payment.domain.PaymentEvent;
import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.payment.domain.PaymentSession;
import com.eventticket.payment.repository.PaymentEventRepository;
import com.eventticket.payment.repository.PaymentSessionRepository;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantPublisher;
import com.eventticket.ticket.usecase.IssueTickets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing that marks an Order paid (requirements/005 criterion 3, KB invariant 19).
 *
 * <p>Everything here is one transaction: the seats are sold, the Order is marked paid, the
 * Tickets are issued and the email is queued together, or none of it happens. That is
 * criterion 8 - never some seats of an Order and not others - and it is why there is no
 * step-by-step orchestration to get wrong.
 *
 * <p>Idempotency (criterion 4) is a constraint, not a check. The delivery is recorded **last**,
 * so a failure leaves no record and the provider's retry is processed rather than mistaken for
 * a repeat; and two deliveries racing each other both do the work but only one can commit the
 * row, so the loser rolls back entirely. The cheap {@code exists} at the top is for the common
 * case, not for correctness.
 *
 * <p>It also confirms refunds, which is why the verification and the duplicate check are here
 * and the refund work is next door in {@link ConfirmRefund}. Providers send both flows down one
 * signed channel with one secret, so there is one place a delivery is authenticated and one
 * table in which it is seen only once. Two entry points would be two copies of the part that
 * must not be got wrong twice.
 */
@Component
public class ConfirmPayment {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPayment.class);

    /** What the caller should tell the provider. Every one of these is an acknowledgement. */
    public enum Outcome {
        APPLIED, DUPLICATE, UNKNOWN_SESSION, ALREADY_SETTLED, FAILED, HOLDS_LAPSED,
        /** Genuine, and about something this system has no opinion on. */
        NOT_ACTIONABLE,
    }

    private final PaymentSessionRepository sessions;
    private final PaymentEventRepository deliveries;
    private final OrderRepository orders;
    private final EventSeatAvailability availability;
    private final IssueTickets issueTickets;
    private final TenantPublisher tenant;
    private final AuditTrail audit;
    private final ConfirmRefund confirmRefund;
    private final Map<String, PaymentProvider> providers;

    public ConfirmPayment(PaymentSessionRepository sessions, PaymentEventRepository deliveries,
                   OrderRepository orders, EventSeatAvailability availability, IssueTickets issueTickets,
                   TenantPublisher tenant, AuditTrail audit, ConfirmRefund confirmRefund,
                   List<PaymentProvider> providers) {
        this.sessions = sessions;
        this.deliveries = deliveries;
        this.orders = orders;
        this.availability = availability;
        this.issueTickets = issueTickets;
        this.tenant = tenant;
        this.audit = audit;
        this.confirmRefund = confirmRefund;
        this.providers = providers.stream()
                .collect(Collectors.toMap(PaymentProvider::name, Function.identity()));
    }

    @Transactional
    public Outcome confirm(String providerName, byte[] rawBody, Map<String, String> headers) {
        PaymentProvider provider = providers.get(providerName);
        if (provider == null) {
            throw ApiException.notFound("Payment provider");
        }

        // criterion 7. Before anything is read out of the payload, let alone acted on.
        PaymentProvider.Confirmation confirmation = provider.verify(rawBody, headers).orElse(null);
        if (confirmation == null) {
            // Authentic, and about something this system does not act on. Distinct from a
            // confirmation for a session nobody has, which is a warning worth reading: a real
            // provider sends many of these per payment and treating them alike would bury the
            // one that matters under the ones that do not.
            return Outcome.NOT_ACTIONABLE;
        }

        if (deliveries.existsByProviderAndProviderEventId(providerName, confirmation.providerEventId())) {
            log.info("Ignoring repeat webhook provider={} eventId={}",
                    providerName, confirmation.providerEventId());
            return Outcome.DUPLICATE;
        }

        // The kind comes from the verified payload, never from the path. A refund's reference
        // is not a session's, so routing on it after the fact would find no session and
        // acknowledge a real confirmation as a stale one.
        if (confirmation.kind() == PaymentProvider.Kind.REFUND) {
            return confirmRefund.settle(providerName, confirmation);
        }

        PaymentSession session = sessions
                .findByProviderAndProviderRef(providerName, confirmation.providerRef())
                .orElse(null);
        if (session == null) {
            // criterion 6: acknowledged and ignored. A provider retrying against a session we
            // never had is noise, and answering with an error makes it retry forever.
            log.warn("Webhook for unknown session provider={} ref={}",
                    providerName, confirmation.providerRef());
            return Outcome.UNKNOWN_SESSION;
        }
        if (session.isSettled()) {
            return Outcome.ALREADY_SETTLED;
        }

        // From here the work touches tenant-scoped rows. The session carries the tenant
        // precisely so an untenanted request can adopt it once and then obey the policies.
        tenant.adopt(session.buyerUserId(), session.organizationId());

        Outcome outcome = apply(session, confirmation);
        deliveries.save(new PaymentEvent(providerName, confirmation.providerEventId(), session.id()));
        return outcome;
    }

    private Outcome apply(PaymentSession session, PaymentProvider.Confirmation confirmation) {
        Order order = orders.findOrThrow(session.orderId());

        if (!confirmation.succeeded()) {
            session.failed();
            sessions.save(session);
            log.info("Payment failed orderId={} sessionId={}", order.id(), session.id());
            return Outcome.FAILED;
        }

        // Selling the seats *is* the check that the holds survived. Asking first and then
        // selling would be two statements with a race between them.
        List<UUID> sold = availability.sell(order.id());
        long owed = order.seats().size();

        if (sold.size() != owed) {
            return keepNothing(session, order, sold.size(), owed);
        }

        order.paid();
        orders.save(order);
        session.paid();
        sessions.save(session);
        issueTickets.issue(new IssueTickets.Request(order.organizationId(), order.buyerUserId(),
                order.id(), order.eventId(),
                order.seats().stream()
                        .map(seat -> new IssueTickets.Request.Seat(
                                seat.eventSeatId(), seat.label(), seat.tierName()))
                        .toList()));

        audit.record(order.organizationId(), AuditTrail.ORDER_PAID, order.id().toString());
        log.info("Confirmed payment orderId={} sessionId={} seats={}",
                order.id(), session.id(), sold.size());
        return Outcome.APPLIED;
    }

    /**
     * requirements/005 criterion 9. The money arrived after the seats went back on sale, so the
     * Order fails and is flagged for refund rather than quietly keeping it. Whatever fraction
     * of the seats was still held is released, because half an order is not an order.
     */
    private Outcome keepNothing(PaymentSession session, Order order, int sold, long owed) {
        availability.release(order.id());
        order.expiredWithPaymentTaken();
        orders.save(order);
        session.paid();
        sessions.save(session);

        audit.record(order.organizationId(), AuditTrail.ORDER_REFUND_REQUIRED, order.id().toString());
        log.warn("Payment arrived after holds lapsed orderId={} sessionId={} seatsStillHeld={} of {}"
                        + " - order failed and flagged for refund",
                order.id(), session.id(), sold, owed);
        return Outcome.HOLDS_LAPSED;
    }
}
