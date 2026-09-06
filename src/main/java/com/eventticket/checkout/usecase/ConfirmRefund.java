package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.repository.EventSeatAvailability;
import com.eventticket.payment.domain.PaymentEvent;
import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.payment.domain.Refund;
import com.eventticket.payment.repository.PaymentEventRepository;
import com.eventticket.payment.repository.RefundRepository;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.money.Money;
import com.eventticket.shared.tenancy.TenantPublisher;
import com.eventticket.shared.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The other half of a refund: the provider saying whether the money actually moved
 * (requirements/008 criterion 2).
 *
 * <p>Reached from {@link ConfirmPayment}, which owns the one place a delivery is authenticated
 * and the one table in which it is seen only once. This is a plain method rather than its own
 * transaction on purpose - it runs inside that one, so a delivery that half-applies leaves no
 * record of itself and the provider's retry is processed rather than mistaken for a duplicate.
 *
 * <p>The Tickets were already voided when the refund was asked for - a Ticket that still
 * admits while a refund is in flight is somebody getting in and getting their money back. The
 * seats wait until here (criterion 5): selling one to somebody else before the money has
 * actually gone back would leave the first buyer without either, if the refund then failed.
 */
@Component
public class ConfirmRefund {

    private static final Logger log = LoggerFactory.getLogger(ConfirmRefund.class);

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final EventSeatAvailability availability;
    private final PaymentEventRepository deliveries;
    private final TenantPublisher tenant;
    private final AuditTrail audit;
    private final EmailSender email;
    private final UserDirectory users;

    public ConfirmRefund(RefundRepository refunds, OrderRepository orders,
                  EventSeatAvailability availability, PaymentEventRepository deliveries,
                  TenantPublisher tenant, AuditTrail audit, EmailSender email,
                  UserDirectory users) {
        this.refunds = refunds;
        this.orders = orders;
        this.availability = availability;
        this.deliveries = deliveries;
        this.tenant = tenant;
        this.audit = audit;
        this.email = email;
        this.users = users;
    }

    public ConfirmPayment.Outcome settle(String providerName, PaymentProvider.Confirmation confirmation) {
        Refund refund = refunds
                .findByProviderAndProviderRef(providerName, confirmation.providerRef())
                .orElse(null);
        if (refund == null) {
            // Acknowledged and ignored, like a payment for a session we never had. A provider
            // retrying against a refund we do not hold is noise, and an error makes it retry
            // forever.
            log.warn("Webhook for unknown refund provider={} ref={}",
                    providerName, confirmation.providerRef());
            return ConfirmPayment.Outcome.UNKNOWN_SESSION;
        }
        if (refund.isSettled()) {
            // A failure arriving for a refund that already succeeded is not a duplicate. It is
            // the provider taking a settlement back (requirements/008 criterion 11), and this
            // is the line the money fell through: Stripe reports a doomed refund as succeeded
            // three times before saying failed, and every one of those later events was being
            // discarded here as a re-delivery.
            if (!confirmation.succeeded() && refund.isRefunded()) {
                tenant.adopt(refund.buyerUserId(), refund.organizationId());
                ConfirmPayment.Outcome reversed = reverse(refund, confirmation);
                deliveries.save(PaymentEvent.forRefund(providerName,
                        confirmation.providerEventId(), refund.id()));
                return reversed;
            }
            return ConfirmPayment.Outcome.ALREADY_SETTLED;
        }

        tenant.adopt(refund.buyerUserId(), refund.organizationId());

        ConfirmPayment.Outcome outcome = apply(refund, confirmation);
        deliveries.save(PaymentEvent.forRefund(providerName, confirmation.providerEventId(), refund.id()));
        return outcome;
    }

    private ConfirmPayment.Outcome apply(Refund refund, PaymentProvider.Confirmation confirmation) {
        Order order = orders.findOrThrow(refund.orderId());

        if (!confirmation.succeeded()) {
            String cause = confirmation.failureReason() == null
                    ? "The provider refused the refund." : confirmation.failureReason();
            refund.failed(cause);
            refunds.save(refund);
            // The Order is deliberately left as it was. It is still holding the buyer's money,
            // and saying otherwise would hide the one thing somebody has to act on. The
            // Tickets stay void: nobody should be admitted on an Order somebody is trying to
            // refund, and a second attempt is the way out.
            log.warn("Refund failed orderId={} refundId={} cause={}", order.id(), refund.id(), cause);
            return ConfirmPayment.Outcome.FAILED;
        }

        refund.refunded();
        refunds.save(refund);
        order.refunded();
        orders.save(order);

        // criterion 5, and only now. Zero for a cancelled or closed Event, which is not a
        // failure - there is no sale for the seat to go back into.
        int freed = availability.releaseSold(order.id());

        audit.record(order.organizationId(), AuditTrail.ORDER_REFUNDED, order.id().toString());
        notifyBuyer(refund, order);

        log.info("Refund settled orderId={} refundId={} amount={} seatsBackOnSale={}",
                order.id(), refund.id(), refund.amount().amount(), freed);
        return ConfirmPayment.Outcome.APPLIED;
    }

    /**
     * requirements/008 criterion 11: what to do when the money comes back out.
     *
     * <p>Both halves, because they answer different questions. The Refund records what actually
     * happened - it says REFUND_FAILED with the provider's reason, which is the only honest
     * account of where the money is. The Order is flagged, because a record nobody is looking
     * at changes nothing and by this point somebody has to look: the buyer has an email saying
     * they were refunded and it is not true.
     *
     * <p>The seats are left on sale. They were released when the refund settled and may have
     * been sold since; pulling them back would take a seat from a second buyer to fix the
     * first one's money, which is the wrong trade. The Tickets stay void for the same reason
     * they were voided in the first place - nobody is getting in on this Order.
     */
    private ConfirmPayment.Outcome reverse(Refund refund, PaymentProvider.Confirmation confirmation) {
        Order order = orders.findOrThrow(refund.orderId());
        String cause = confirmation.failureReason() == null
                ? "The provider reversed a refund it had reported as settled."
                : confirmation.failureReason();

        refund.failed(cause);
        refunds.save(refund);
        order.refundWasReversed();
        orders.save(order);

        audit.record(order.organizationId(), AuditTrail.ORDER_REFUND_REVERSED, order.id().toString());
        log.warn("Refund reversed after settling orderId={} refundId={} cause={}",
                order.id(), refund.id(), cause);
        return ConfirmPayment.Outcome.FAILED;
    }

    /**
     * The buyer is told when the money has actually gone back, not when it was asked for. A
     * "you have been refunded" that arrives before the provider agrees is the email that
     * generates the support request it was meant to prevent.
     */
    private void notifyBuyer(Refund refund, Order order) {
        email.send(users.emailOf(order.buyerUserId()), "Your order has been refunded",
                """
                %s has been refunded to you.

                %s

                It can take a few days to appear, depending on your bank. Your tickets for this
                order are no longer valid.
                """.formatted(amountOf(refund.amount()), refund.reason()));
    }

    /**
     * Grouped digits and the currency beside them. VND has no minor unit, so the amount is
     * the dong and there is nothing to divide by - the mistake this avoids is the one that
     * is wrong by a factor of a hundred and looks plausible.
     */
    private static String amountOf(Money money) {
        return "%,d %s".formatted(money.amount(), money.currency());
    }
}
