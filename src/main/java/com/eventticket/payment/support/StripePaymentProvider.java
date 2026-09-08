package com.eventticket.payment.support;

import com.eventticket.payment.domain.NextAction;
import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stripe.net.Webhook;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stripe, as the second provider - and the first one whose flow differs from the fake's.
 *
 * <p>That difference is the point. KB ADR-0002 says the abstraction is unproven until something
 * with a genuinely different flow runs through it: the fake displays a QR, Stripe hosts a page,
 * and until this class existed {@code HOSTED_CHECKOUT} was a branch nothing had ever produced -
 * in this module or in the client that renders it.
 *
 * <p>Exists only when configured. No key, no bean, and {@code StartPayment} answers that there
 * is no provider by that name - which is true, and better than a bean that throws on first use.
 * It also keeps the test suite and a fresh clone working with no account and no network.
 */
@Component
@ConditionalOnProperty("app.payment.stripe.secret-key")
public class StripePaymentProvider implements PaymentProvider {

    public static final String NAME = "STRIPE";
    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);

    private final StripeClient stripe;
    private final ObjectMapper json;
    private final String webhookSecret;
    private final String baseUrl;

    public StripePaymentProvider(ObjectMapper json,
                          @Value("${app.payment.stripe.secret-key}") String secretKey,
                          @Value("${app.payment.stripe.webhook-secret}") String webhookSecret,
                          @Value("${app.base-url}") String baseUrl) {
        this.stripe = StripeClient.builder().setApiKey(secretKey).build();
        this.json = json;
        this.webhookSecret = webhookSecret;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A hosted Checkout Session, whose URL is the whole of the next action.
     *
     * <p>The amount is passed through untouched. Stripe takes amounts in a currency's smallest
     * unit and VND has no minor unit, so the dong <em>is</em> the smallest unit - the same fact
     * `nfr.md` states about our own Money, and the reason there is no multiplication here. A
     * hundred thousand dong is {@code 100000}; sending {@code 10000000} would charge a hundred
     * times the price and look plausible in every log.
     *
     * <p>The Order id goes in {@code client_reference_id} and in metadata, but nothing reads it
     * back: a confirmation is matched by the session id this returns, which is what the Payment
     * Session stores. It is there for the person looking at a Stripe dashboard trying to work
     * out which order a payment belongs to.
     */
    @Override
    public Started start(Attempt attempt) {
        String amountLabel = attempt.total().amount() + " " + attempt.total().currency();
        try {
            Session session = stripe.checkout().sessions().create(SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setClientReferenceId(attempt.orderId().toString())
                    .putMetadata("orderId", attempt.orderId().toString())
                    .setCustomerEmail(attempt.buyerEmail())
                    // Back to the Order either way. Returning is not what pays for anything
                    // (requirements/005 criterion 3) - the page says so and waits for the
                    // webhook - so success and cancel differ only in what the page then shows.
                    .setSuccessUrl(baseUrl + "/orders/" + attempt.orderId())
                    .setCancelUrl(baseUrl + "/orders/" + attempt.orderId())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(attempt.total().currency().name().toLowerCase(java.util.Locale.ROOT))
                                    .setUnitAmount(attempt.total().amount())
                                    .setProductData(SessionCreateParams.LineItem.PriceData
                                            .ProductData.builder()
                                            .setName("Tickets")
                                            .build())
                                    .build())
                            .build())
                    .build());

            log.info("Started Stripe checkout orderId={} session={} amount={}",
                    attempt.orderId(), session.getId(), amountLabel);

            return new Started(session.getId(), NextAction.hostedCheckout(session.getUrl()),
                    Instant.ofEpochSecond(session.getExpiresAt()));
        } catch (StripeException failed) {
            throw unavailable("start a payment", failed);
        }
    }

    /**
     * Stripe refunds a PaymentIntent, and what we hold is the Checkout Session that produced
     * it - so the session is fetched to find the charge before anything can be given back.
     *
     * <p>The extra call is the price of the abstraction being right: a Payment Session's
     * reference is whatever the provider handed us at the start, and demanding that every
     * provider hand back the same *kind* of handle would be modelling Stripe's internals in a
     * port that also has to fit a bank transfer.
     */
    @Override
    public Reversal refund(RefundAttempt attempt) {
        try {
            Session session = stripe.checkout().sessions().retrieve(attempt.paymentRef());
            String paymentIntent = session.getPaymentIntent();
            if (paymentIntent == null) {
                // A session that was never paid has nothing to reverse. Refusing here is
                // right: the alternative is a refund row waiting for a confirmation that no
                // provider will ever send.
                throw new ApiException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                        "That payment never completed at the provider, so there is nothing to "
                                + "refund.");
            }

            Refund refund = stripe.refunds().create(RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntent)
                    .setAmount(attempt.amount().amount())
                    .putMetadata("orderId", attempt.orderId().toString())
                    .putMetadata("reason", attempt.reason() == null ? "" : attempt.reason())
                    .build());

            log.info("Started Stripe refund orderId={} refund={} intent={}",
                    attempt.orderId(), refund.getId(), paymentIntent);
            return new Reversal(refund.getId());
        } catch (StripeException failed) {
            throw unavailable("start a refund", failed);
        }
    }

    /**
     * requirements/005 criterion 7, using Stripe's own verification.
     *
     * <p>Over the raw bytes, which is the part that transfers between providers: a body parsed
     * and serialised again has different whitespace and key order, and therefore a different
     * signature. The scheme underneath has a timestamp tolerance and accepts more than one
     * signature per delivery so that a secret can be rotated without dropping events - which is
     * exactly the kind of thing to use a library for rather than write.
     */
    @Override
    public Optional<Confirmation> verify(byte[] rawBody, Map<String, String> headers) {
        String signature = headers.get("stripe-signature");
        if (signature == null) {
            throw new ApiException(ErrorCodes.NOT_PERMITTED, "That delivery is not signed.");
        }
        Event event;
        try {
            event = Webhook.constructEvent(new String(rawBody, StandardCharsets.UTF_8),
                    signature, webhookSecret);
        } catch (SignatureVerificationException rejected) {
            throw new ApiException(ErrorCodes.NOT_PERMITTED,
                    "That delivery did not come from Stripe.");
        }
        return confirmationOf(event);
    }

    /**
     * Which of Stripe's many event types this system has an opinion about.
     *
     * <p>Deliberately few. Stripe sends dozens per payment and every one this does not name is
     * acknowledged and ignored by the caller, which is what requirements/005 criterion 6 asks
     * for - an endpoint that errored on an event it did not recognise would turn one delivery
     * into a retry loop.
     *
     * <p>The kind comes from the event type rather than from the endpoint, because payments and
     * refunds arrive down the same signed channel. A refund's reference means nothing in the
     * sessions table, and looking it up there would be indistinguishable from a stale delivery.
     */
    private Optional<Confirmation> confirmationOf(Event event) {
        return switch (event.getType()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    Optional.of(new Confirmation(Kind.PAYMENT, event.getId(), sessionIdOf(event),
                            true, null));
            case "checkout.session.expired", "checkout.session.async_payment_failed" ->
                    Optional.of(new Confirmation(Kind.PAYMENT, event.getId(), sessionIdOf(event),
                            false, "The payment was not completed."));
            // `refund.failed` is the one that was missing, and the one that means it. Stripe
            // answers a doomed refund with created/updated/charge.refund.updated all saying
            // succeeded, and only then failed - so the event named after the outcome is the
            // event that carries the truth (requirements/008 criterion 11).
            case "refund.created", "refund.updated", "refund.failed", "charge.refund.updated" ->
                    refundOf(event);
            // Genuine, and about something this system has no opinion on. A single payment
            // produces a dozen of these; they are acknowledged and go no further.
            default -> {
                log.debug("Stripe event {} is not one this system acts on", event.getType());
                yield Optional.empty();
            }
        };
    }

    private Optional<Confirmation> refundOf(Event event) {
        JsonNode refund = payloadOf(event);
        String status = text(refund, "status");
        if (status == null) {
            return Optional.of(new Confirmation(Kind.REFUND, event.getId(), null, false,
                    "A refund event arrived that could not be read."));
        }
        // Stripe's refund statuses: pending, requires_action, succeeded, failed, canceled. Only
        // the last three are an answer; pending is the state the Refund is already in here, and
        // reporting it as a failure would close an attempt that is still running.
        return switch (status) {
            case "succeeded" -> Optional.of(new Confirmation(Kind.REFUND, event.getId(),
                    text(refund, "id"), true, null));
            case "failed", "canceled" -> Optional.of(new Confirmation(Kind.REFUND, event.getId(),
                    text(refund, "id"), false,
                    "Stripe could not return the money (" + status + ")."));
            // Still running. Reporting it would close an attempt that has not finished.
            default -> Optional.empty();
        };
    }

    /**
     * The Checkout Session id, which is what {@link #start} returned and what the Payment
     * Session stores.
     */
    private String sessionIdOf(Event event) {
        return text(payloadOf(event), "id");
    }

    /**
     * The event's object as plain JSON rather than as a typed Stripe model.
     *
     * <p>An event built under an API version other than the SDK's does not deserialise into a
     * typed object at all - {@code getObject()} answers empty and the reason is a version
     * mismatch nobody chose. Two fields read by name survive that, and this needs exactly two.
     */
    private JsonNode payloadOf(Event event) {
        String raw = event.getDataObjectDeserializer().getRawJson();
        return raw == null ? null : json.readTree(raw);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /**
     * requirements/005 criterion 13: a provider that answers is not a provider that is down.
     *
     * <p>Every {@link StripeException} used to become "could not be reached. Try again in a
     * moment." Stripe had usually been reached and had said no - {@code amount_too_small} for a
     * price below its floor, for instance - and would say it again every time. So a buyer was
     * invited to keep pressing a button that would never work, while the organizer, whose
     * pricing caused it, heard nothing at all.
     *
     * <p>Split by whether trying again could plausibly change the answer. Reaching Stripe and
     * being refused is not an outage, and calling it one costs the one thing an error message
     * is for: knowing whether to wait or to do something.
     *
     * <p>Stripe's own words are logged and not returned. They are written for whoever wrote
     * this code - "must convert to at least 50 cents" is about an account's presentment
     * currency - and the error envelope is the one place a request's own content should not be
     * echoed back. The code and the message go to the log, where the organizer's problem is
     * diagnosable.
     */
    private ApiException unavailable(String what, StripeException failed) {
        if (isTransient(failed)) {
            log.warn("Stripe could not {}: {}", what, failed.getMessage());
            return new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "The payment provider could not be reached. Try again in a moment.");
        }
        log.warn("Stripe refused to {}: {}; code={}", what, failed.getMessage(), failed.getCode());
        return new ApiException(ErrorCodes.VALIDATION_FAILED,
                "The payment provider refused this payment. Trying again will not help - "
                        + "please tell the organizer.");
    }

    /**
     * Worth retrying: nothing about the request was wrong, so the same request may succeed.
     *
     * <p>A connection that never landed, a rate limit that will lift, and Stripe's own 5xx.
     * Everything else - an invalid request, a declined card, a key that is not valid, a
     * permission the account does not have - is an answer, and answers do not change by being
     * asked again.
     */
    static boolean isTransient(StripeException failed) {
        return failed instanceof ApiConnectionException
                || failed instanceof RateLimitException
                || failed instanceof com.stripe.exception.ApiException;
    }
}
