package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.payment.domain.PaymentSession;
import com.eventticket.payment.domain.Refund;
import com.eventticket.payment.repository.PaymentSessionRepository;
import com.eventticket.payment.repository.RefundRepository;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.ticket.domain.Ticket;
import com.eventticket.ticket.repository.TicketRepository;
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
 * Giving the money back (requirements/008 criteria 1 to 5).
 *
 * <p>Two things happen at two different times here, on purpose.
 *
 * <p><b>The Tickets are voided now</b>, when the refund is asked for, not when it settles. A
 * provider can take hours, and a Ticket that still admits during that window lets somebody walk
 * through the door <em>and</em> keep their money. Voiding first means the worst case of a
 * refund that later fails is a person who has to be let in by hand, rather than a person who
 * got in for nothing.
 *
 * <p><b>The seats go back on sale later</b>, when the provider says the money moved
 * (criteria 2 and 5). Putting a seat back at the moment a refund is *asked for* would sell it
 * to somebody else while the refund is still in flight - and if that refund then failed, the
 * first buyer would be out both their money and their seat, with nothing to undo. In between,
 * the seat admits nobody and belongs to nobody, which is reversible and true.
 *
 * <p>So the Order becomes REFUNDED at the same later moment. A paid Order with a pending
 * refund and void Tickets is exactly what is true meanwhile; recording it as refunded up front
 * would be a boolean standing in for a lifecycle, which is what ADR-0002 refuses.
 */
@Component
public class RefundOrder {

    private static final Logger log = LoggerFactory.getLogger(RefundOrder.class);

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final PaymentSessionRepository sessions;
    private final TicketRepository tickets;
    private final Managers managers;
    private final AuditTrail audit;
    private final Map<String, PaymentProvider> providers;

    public RefundOrder(OrderRepository orders, RefundRepository refunds,
                PaymentSessionRepository sessions, TicketRepository tickets,
                Managers managers, AuditTrail audit, List<PaymentProvider> providers) {
        this.orders = orders;
        this.refunds = refunds;
        this.sessions = sessions;
        this.tickets = tickets;
        this.managers = managers;
        this.audit = audit;
        this.providers = providers.stream()
                .collect(Collectors.toMap(PaymentProvider::name, Function.identity()));
    }

    @Transactional
    public Refund refund(UUID orderId, String reason) {
        UUID organizationId = TenantContext.requireOrganizationId();
        // criterion 1: an Owner or Manager, never the buyer. Gate Staff are refused by the
        // same guard that keeps them away from events, which is the line the server draws.
        managers.requireCallerCanManageEvents(organizationId);

        Order order = orders.findOrThrow(orderId);
        return refund(order, reason);
    }

    /**
     * The same work, for a caller that has already established who it is and which Order it
     * has - which is the Event cancellation, refunding many Orders one transaction at a time.
     */
    @Transactional
    public Refund refund(Order order, String reason) {
        order.requireRefundable();
        requireNobodyHasBeenLetIn(order);

        // A provider reverses a charge, not an order, so the successful attempt is what this
        // needs. An Order with money and no settled session is not a state the system produces.
        PaymentSession settled = sessions.findByOrderIdAndStatus(order.id(), PaymentSession.Status.PAID)
                .orElseThrow(() -> new ApiException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                        "No settled payment was found for this order, so there is nothing to reverse."));

        PaymentProvider provider = providers.get(settled.provider());
        if (provider == null) {
            // The provider that took the money is no longer configured. Refusing is the honest
            // answer: pretending to refund through a different one would move somebody else's.
            throw new ApiException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                    "The payment provider this order was paid through is no longer available.");
        }

        PaymentProvider.Reversal reversal = provider.refund(new PaymentProvider.RefundAttempt(
                order.id(), order.total(), settled.providerRef(), reason));

        Refund refund = refunds.save(new Refund(order.id(), order.organizationId(),
                order.buyerUserId(), provider.name(), reversal.providerRef(),
                order.total(), reason));

        voidTickets(order);

        audit.record(order.organizationId(), AuditTrail.ORDER_REFUND_STARTED, order.id().toString());
        log.info("Started refund orderId={} refundId={} provider={} amount={}",
                order.id(), refund.id(), provider.name(), order.total().amount());
        return refund;
    }

    /**
     * criterion 3 and KB invariant 21. Somebody has already been let in, and no refund takes
     * that back - so the whole Order is refused rather than the redeemed seats being deducted
     * from it. Partial refunds are not in the contract, and inventing one here would be
     * inventing a policy about what an admitted person owes.
     */
    private void requireNobodyHasBeenLetIn(Order order) {
        long redeemed = tickets.countByOrderIdAndStatus(order.id(), Ticket.Status.REDEEMED);
        if (redeemed > 0) {
            log.warn("Refund refused orderId={}: {} ticket(s) already redeemed", order.id(), redeemed);
            throw new ApiException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                    redeemed == 1
                            ? "A ticket on this order has already been used at the door."
                            : redeemed + " tickets on this order have already been used at the door.");
        }
    }

    /** criterion 4, and now rather than later: see the note at the top of this class. */
    private void voidTickets(Order order) {
        List<Ticket> issued = tickets.findByOrderIdOrderBySeatLabelAsc(order.id());
        issued.forEach(Ticket::voided);
        tickets.saveAll(issued);
        log.info("Voided {} ticket(s) for orderId={}", issued.size(), order.id());
    }
}
