package com.eventticket.payment.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * requirements/005 criterion 13: a provider that answers is not a provider that is down.
 *
 * <p>Every {@code StripeException} used to become "could not be reached. Try again in a moment."
 * The one that prompted this was {@code amount_too_small}, for a ticket priced at 30 ₫: Stripe
 * had been reached, had refused, and would refuse again every time - so a buyer was told to
 * retry a thing that could never work, while the organizer whose pricing caused it heard
 * nothing.
 *
 * <p>The question is only ever "could the same request succeed later". That is what decides
 * whether somebody should wait or do something, and it is the whole content of the message.
 */
class StripeFailureTest {

    @Test
    @DisplayName("a connection that never landed is worth trying again")
    void aConnectionFailureIsTransient() {
        assertThat(StripePaymentProvider.isTransient(
                new ApiConnectionException("connection reset"))).isTrue();
    }

    @Test
    @DisplayName("a rate limit lifts")
    void aRateLimitIsTransient() {
        assertThat(StripePaymentProvider.isTransient(
                new RateLimitException("slow down", null, null, null, 429, null))).isTrue();
    }

    @Test
    @DisplayName("stripe's own server error is worth trying again")
    void aProviderServerErrorIsTransient() {
        assertThat(StripePaymentProvider.isTransient(
                new com.stripe.exception.ApiException("upstream", null, null, 500, null))).isTrue();
    }

    /** The one that started this: a price below the provider's floor. */
    @Test
    @DisplayName("an amount the provider will not charge is an answer, not an outage")
    void anInvalidRequestIsPermanent() {
        assertThat(StripePaymentProvider.isTransient(new InvalidRequestException(
                "The Checkout Session's total amount must convert to at least 50 cents.",
                "amount", null, "amount_too_small", 400, null))).isFalse();
    }

    @Test
    @DisplayName("a declined card will decline again")
    void aCardFailureIsPermanent() {
        assertThat(StripePaymentProvider.isTransient(new CardException(
                "declined", null, "card_declined", "number", null, null, 402, null))).isFalse();
    }

    /**
     * Our configuration, not the buyer's problem - and still not something retrying fixes.
     * Telling somebody to try again while the account has no valid key is the same lie in a
     * different costume.
     */
    @Test
    @DisplayName("a key or permission problem is ours, and permanent either way")
    void anAccountProblemIsPermanent() {
        assertThat(StripePaymentProvider.isTransient(
                new AuthenticationException("no such key", null, null, 401))).isFalse();
        assertThat(StripePaymentProvider.isTransient(
                new PermissionException("not allowed", null, null, 403))).isFalse();
    }
}
