package com.eventticket.payment.domain;

import com.eventticket.shared.money.Money;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A payment provider, modelled by what it does rather than by how it is configured.
 *
 * <p>There is no {@code charge}. Starting an attempt returns a next action, and money is
 * confirmed later by {@link #verify} handling a callback the provider makes. Both halves are
 * necessary because these providers differ in flow: a redirect, a QR to display, a hosted
 * page. A design with a charge method fits exactly one of those (KB ADR-0002).
 *
 * <p>{@link #refund} is here rather than on a port of its own. A provider that takes money and
 * cannot give it back is not one this platform can use, so the two are one capability; and a
 * second port would be a second registry keyed by the same {@link #name()}, with nothing to
 * stop the two disagreeing about which providers exist. Reversal follows the same shape as
 * payment - started here, settled by a callback - because it is the same shape at every
 * provider.
 */
public interface PaymentProvider {

    /** Matches the {@code provider} in a start request and in a webhook path. */
    public String name();

    /** What the buyer must do next, and the provider's handle for this attempt. */
    public Started start(Attempt attempt);

    /**
     * Asks the provider to reverse a settled payment. requirements/008 criterion 2: this
     * starts a refund and never completes one - the answer arrives at {@link #verify} like
     * any other confirmation.
     */
    public Reversal refund(RefundAttempt attempt);

    /**
     * requirements/005 criterion 7: authenticity is verified before the payload is trusted.
     * Throws rather than returning a failure, because a caller that could accidentally use an
     * unverified confirmation is the thing this signature exists to prevent.
     */
    public Confirmation verify(byte[] rawBody, Map<String, String> headers);

    public record Attempt(UUID orderId, Money total, Instant holdExpiresAt, String buyerEmail) {}

    public record Started(String providerRef, NextAction nextAction, Instant expiresAt) {}

    /**
     * @param paymentRef the handle of the settled payment being reversed. A provider refunds a
     *                   charge, not an order, so this is the reference from the successful
     *                   Payment Session rather than anything of ours.
     */
    public record RefundAttempt(UUID orderId, Money amount, String paymentRef, String reason) {}

    public record Reversal(String providerRef) {}

    /**
     * Which of the two flows a delivery is about.
     *
     * <p>Providers send both down one signed channel, so this is read from the payload after
     * the signature has been checked and never from the path. A refund confirmation carries a
     * refund's reference, and looking it up in the sessions table would find nothing - which
     * would be indistinguishable from a stale delivery and acknowledged as one.
     */
    public enum Kind { PAYMENT, REFUND }

    /**
     * @param providerEventId the provider's identifier for this delivery, which is what makes
     *                        a repeat recognisable as one
     * @param providerRef     the attempt being confirmed - a Payment Session's or a Refund's,
     *                        according to {@code kind}
     * @param succeeded       false for a definitive failure; the attempt is closed either way
     */
    public record Confirmation(Kind kind, String providerEventId, String providerRef,
                               boolean succeeded, String failureReason) {}
}
