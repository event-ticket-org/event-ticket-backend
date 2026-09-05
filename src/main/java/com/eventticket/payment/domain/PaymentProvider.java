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
 */
public interface PaymentProvider {

    /** Matches the {@code provider} in a start request and in a webhook path. */
    public String name();

    /** What the buyer must do next, and the provider's handle for this attempt. */
    public Started start(Attempt attempt);

    /**
     * requirements/005 criterion 7: authenticity is verified before the payload is trusted.
     * Throws rather than returning a failure, because a caller that could accidentally use an
     * unverified confirmation is the thing this signature exists to prevent.
     */
    public Confirmation verify(byte[] rawBody, Map<String, String> headers);

    public record Attempt(UUID orderId, Money total, Instant holdExpiresAt, String buyerEmail) {}

    public record Started(String providerRef, NextAction nextAction, Instant expiresAt) {}

    /**
     * @param providerEventId the provider's identifier for this delivery, which is what makes
     *                        a repeat recognisable as one
     * @param providerRef     the attempt being confirmed
     * @param paid            false for a definitive failure; the session is closed either way
     */
    public record Confirmation(String providerEventId, String providerRef, boolean paid) {}
}
