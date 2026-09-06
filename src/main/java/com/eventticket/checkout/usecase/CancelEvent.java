package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.EventCancellation;
import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.payment.domain.Refund;
import com.eventticket.payment.repository.RefundRepository;
import com.eventticket.shared.UserDirectory;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.ticket.domain.Ticket;
import com.eventticket.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancelling an Event: voiding every Ticket and refunding every paid Order (requirements/008
 * criteria 6 to 9, KB invariant 22).
 *
 * <p>This reads like an {@code event} operation and is not one. {@code event} cannot see
 * Orders, Tickets or providers, and cancelling is all three - so it lives here, with the module
 * that already owns the transaction where money becomes tickets.
 *
 * <p><b>Each Order is its own transaction.</b> Criterion 7 says the operation reports per-Order
 * status because it will partially fail, and one transaction around five hundred refunds
 * cannot: a provider refusing the last would roll back the four hundred and ninety-nine that
 * worked, and the organizer would be told nothing except that it failed.
 *
 * <p>The per-Order boundary is {@link RefundOrder} being {@code @Transactional} on a bean of
 * its own: each call crosses a proxy and gets its own transaction, and a failure rolls back
 * only that one. This class must therefore <b>not</b> be transactional itself. If {@code cancel}
 * ever gains {@code @Transactional}, every refund joins it, one provider refusing the last one
 * marks the whole thing rollback-only, and the four hundred and ninety-nine that worked are
 * undone at commit - with the per-Order report still cheerfully saying they succeeded.
 *
 * <p>The {@link TransactionTemplate} is here for the steps that are private to this class, and
 * so get no proxy and no annotation: marking the Event cancelled, and reading the progress back.
 *
 * <p>The work runs inline rather than on a queue. `nfr.md` puts this platform on one instance
 * with one database and no broker, and a background thread that dies with the process would
 * leave a cancellation half done with nothing to resume it. The endpoint answers 202 and the
 * caller polls, which is the contract's shape; what it polls is the refunds themselves.
 */
@Component
public class CancelEvent {

    private static final Logger log = LoggerFactory.getLogger(CancelEvent.class);

    /**
     * The "provider" on a refusal that never reached one. It is not a provider name and is not
     * looked up as one - no callback can ever name it, because nobody was asked.
     */
    private static final String REFUSED_BY_US = "NONE";

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final RefundRepository refunds;
    private final RefundOrder refundOrder;
    private final Managers managers;
    private final AuditTrail audit;
    private final EmailSender email;
    private final UserDirectory users;
    private final TransactionTemplate transactions;

    public CancelEvent(EventRepository events, OrderRepository orders, TicketRepository tickets,
                RefundRepository refunds, RefundOrder refundOrder, Managers managers,
                AuditTrail audit, EmailSender email, UserDirectory users,
                PlatformTransactionManager transactionManager) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.refunds = refunds;
        this.refundOrder = refundOrder;
        this.managers = managers;
        this.audit = audit;
        this.email = email;
        this.users = users;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public EventCancellation cancel(UUID eventId, String reason) {
        Event event = transactions.execute(status -> markCancelled(eventId, reason));

        List<Order> paid = transactions.execute(status ->
                orders.findByEventIdAndStatus(eventId, Order.Status.PAID));
        log.info("Cancelling eventId={} with {} paid order(s) to refund", eventId, paid.size());

        for (Order order : paid) {
            refundOne(order.id(), reason);
        }

        notifyTicketHolders(event, paid, reason);
        // Through the template, not as a bare call: `progress` is annotated for the polling
        // endpoint, which reaches it across a proxy, and a call from inside this class would
        // go straight past that annotation.
        return transactions.execute(status -> progress(eventId));
    }

    /**
     * The Event is cancelled first and alone. Everything after it is an attempt that may fail,
     * and no failure should be able to leave an Event still selling tickets to something that
     * is not happening.
     */
    private Event markCancelled(UUID eventId, String reason) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        event.cancel(reason, Instant.now());
        events.save(event);

        // Every Ticket, not only the ones on paid Orders: a Ticket admits nobody to an event
        // that is not happening, however it was come by.
        List<Ticket> issued = tickets.findByEventId(eventId);
        issued.forEach(Ticket::voided);
        tickets.saveAll(issued);

        audit.record(organizationId, AuditTrail.EVENT_CANCELLED, event.title());
        log.info("Cancelled eventId={} and voided {} ticket(s)", eventId, issued.size());
        return event;
    }

    /**
     * One Order, one transaction - {@code RefundOrder.refund} supplies the boundary. The Order
     * is re-read inside it rather than carried in, because it was loaded in a transaction that
     * has since closed, and a detached entity written back is how a stale value overwrites a
     * fresh one.
     */
    private void refundOne(UUID orderId, String reason) {
        try {
            transactions.execute(status -> refundOrder.refund(orders.findOrThrow(orderId), reason));
        } catch (RuntimeException failure) {
            // Recorded rather than thrown. The organizer needs the list of Orders that did not
            // go through, and an exception here would replace that list with one message about
            // whichever Order happened to be first.
            log.warn("Refund failed during cancellation orderId={} cause={}",
                    orderId, failure.getMessage());
            recordRefusal(orderId, reason, failure.getMessage());
        }
    }

    /**
     * criterion 7, and the half of it that was missing: a refusal is an outcome, and an Order
     * with no refund row at all reads as "still going" for ever on the screen somebody is
     * watching to find out what they have to finish by hand. The log is not where they look.
     *
     * <p>Its own transaction, because the one that just failed is gone.
     */
    private void recordRefusal(UUID orderId, String reason, String cause) {
        try {
            transactions.execute(status -> {
                Order order = orders.findOrThrow(orderId);
                return refunds.save(Refund.refused(order.id(), order.organizationId(),
                        order.buyerUserId(), REFUSED_BY_US, order.total(), reason,
                        cause == null ? "The refund was refused." : cause));
            });
        } catch (RuntimeException unrecordable) {
            // Nothing more to do. The refund already failed; failing to write that down must
            // not stop the Orders after this one from being attempted.
            log.error("Could not record the refusal for orderId={}", orderId, unrecordable);
        }
    }

    /**
     * criterion 8. One email per buyer rather than per Ticket: somebody with four seats is one
     * person, and four identical messages is how a cancellation notice becomes spam.
     */
    private void notifyTicketHolders(Event event, List<Order> affected, String reason) {
        Map<UUID, String> addresses = users.emailsOf(
                affected.stream().map(Order::buyerUserId).distinct().toList());
        addresses.values().forEach(address -> email.send(address,
                "%s has been cancelled".formatted(event.title()),
                """
                %s has been cancelled.

                %s

                Your tickets are no longer valid, and the money you paid is on its way back to
                you. It can take a few days to appear, depending on your bank.
                """.formatted(event.title(), reason)));
    }

    /**
     * What the caller polls (criterion 7): every Order the cancellation had to give money back
     * for, and what became of each attempt.
     */
    @Transactional(readOnly = true)
    public EventCancellation progress(UUID eventId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        // Both statuses, because an Order that has been refunded is no longer PAID and
        // dropping it would make a finished cancellation look like an empty one.
        List<UUID> affected = java.util.stream.Stream
                .concat(orders.findByEventIdAndStatus(eventId, Order.Status.PAID).stream(),
                        orders.findByEventIdAndStatus(eventId, Order.Status.REFUNDED).stream())
                .map(Order::id).toList();

        Map<UUID, Refund> byOrder = refunds.findByOrderIdIn(affected).stream()
                .collect(Collectors.toMap(Refund::orderId, Function.identity(), (a, b) -> a));

        return EventCancellation.of(event, affected, byOrder);
    }
}
