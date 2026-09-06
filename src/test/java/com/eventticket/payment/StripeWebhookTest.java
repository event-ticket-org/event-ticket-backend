package com.eventticket.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.payment.support.StripePaymentProvider;
import com.eventticket.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The half of the Stripe provider that can be tested without Stripe.
 *
 * <p>Deliberately not a test of `start` or `refund`: those are two HTTP calls to somebody
 * else's service, and a test that mocked them would assert that the mock was configured the
 * way the test configured it. What is worth pinning here is everything that happens to a
 * delivery after it arrives - whether the signature is checked at all, and which of Stripe's
 * many event types this system has an opinion about - because those are decisions of ours and
 * they fail silently.
 *
 * <p>The signature is built here rather than taken from a fixture, so the test would notice a
 * verification that had quietly stopped verifying.
 */
class StripeWebhookTest {

    private static final String SECRET = "whsec_a_secret_that_is_only_used_here";

    private final StripePaymentProvider provider = new StripePaymentProvider(
            new ObjectMapper(), "sk_test_unused_in_these_tests", SECRET,
            "http://localhost:5173");

    @Test
    @DisplayName("a completed checkout is a payment, identified by its session")
    void aCompletedCheckoutConfirmsThePayment() {
        var confirmation = verify(event("evt_1", "checkout.session.completed", """
                {"id": "cs_test_123", "object": "checkout.session", "payment_status": "paid"}"""));

        assertThat(confirmation).isPresent().get().satisfies(it -> {
            assertThat(it.kind()).isEqualTo(PaymentProvider.Kind.PAYMENT);
            assertThat(it.providerEventId()).isEqualTo("evt_1");
            // The Checkout Session id, which is what `start` returned and the Payment Session
            // stores. Anything else here would be a confirmation nothing can be matched to.
            assertThat(it.providerRef()).isEqualTo("cs_test_123");
            assertThat(it.succeeded()).isTrue();
        });
    }

    @Test
    @DisplayName("an expired checkout closes the attempt rather than leaving it open")
    void anExpiredCheckoutFailsThePayment() {
        var confirmation = verify(event("evt_2", "checkout.session.expired", """
                {"id": "cs_test_456", "object": "checkout.session"}"""));

        assertThat(confirmation).isPresent().get().satisfies(it -> {
            assertThat(it.succeeded()).isFalse();
            assertThat(it.providerRef()).isEqualTo("cs_test_456");
            assertThat(it.failureReason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("a settled refund is a refund, identified by the refund rather than the charge")
    void aSucceededRefundConfirmsTheRefund() {
        var confirmation = verify(event("evt_3", "refund.updated", """
                {"id": "re_test_789", "object": "refund", "status": "succeeded"}"""));

        assertThat(confirmation).isPresent().get().satisfies(it -> {
            assertThat(it.kind()).isEqualTo(PaymentProvider.Kind.REFUND);
            assertThat(it.providerRef()).isEqualTo("re_test_789");
            assertThat(it.succeeded()).isTrue();
        });
    }

    @Test
    @DisplayName("a refund still in flight says nothing, rather than saying it failed")
    void aPendingRefundIsNotAnAnswer() {
        assertThat(verify(event("evt_4", "refund.updated", """
                {"id": "re_test_789", "object": "refund", "status": "pending"}"""))).isEmpty();
    }

    @Test
    @DisplayName("a failed refund is reported, so nobody waits for money that is not coming")
    void aFailedRefundIsReported() {
        var confirmation = verify(event("evt_5", "refund.updated", """
                {"id": "re_test_789", "object": "refund", "status": "failed"}"""));

        assertThat(confirmation).isPresent().get().satisfies(it -> {
            assertThat(it.kind()).isEqualTo(PaymentProvider.Kind.REFUND);
            assertThat(it.succeeded()).isFalse();
        });
    }

    /**
     * The reason {@code verify} answers with an Optional at all. Stripe sends a dozen events
     * for one payment; before this, each of the ten that do not matter was dressed up as a
     * confirmation of nothing and logged as a webhook for an unknown session.
     */
    @Test
    @DisplayName("an event this system has no opinion on is genuine and silent")
    void anIrrelevantEventIsNotAConfirmation() {
        assertThat(verify(event("evt_6", "payment_intent.created", """
                {"id": "pi_test_1", "object": "payment_intent"}"""))).isEmpty();
        assertThat(verify(event("evt_7", "charge.succeeded", """
                {"id": "ch_test_1", "object": "charge"}"""))).isEmpty();
    }

    @Test
    @DisplayName("a body signed with the wrong secret is refused, not read")
    void aForgedDeliveryIsRefused() {
        String body = event("evt_8", "checkout.session.completed", """
                {"id": "cs_test_123", "object": "checkout.session"}""");
        String forged = signature(body, "whsec_not_the_secret", Instant.now());

        assertThatThrownBy(() -> provider.verify(body.getBytes(StandardCharsets.UTF_8),
                Map.of("stripe-signature", forged)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("did not come from Stripe");
    }

    @Test
    @DisplayName("an unsigned delivery is refused")
    void anUnsignedDeliveryIsRefused() {
        String body = event("evt_9", "checkout.session.completed", "{\"id\": \"cs_1\"}");
        assertThatThrownBy(() -> provider.verify(body.getBytes(StandardCharsets.UTF_8), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not signed");
    }

    /**
     * Stripe's scheme includes the timestamp in what is signed and refuses a delivery older
     * than its tolerance, which is what stops a captured body being replayed tomorrow. Signing
     * an hour ago proves the check is live rather than that the SDK was called.
     */
    @Test
    @DisplayName("a correctly signed but stale delivery is refused")
    void aStaleDeliveryIsRefused() {
        String body = event("evt_10", "checkout.session.completed", "{\"id\": \"cs_1\"}");
        String old = signature(body, SECRET, Instant.now().minusSeconds(3600));

        assertThatThrownBy(() -> provider.verify(body.getBytes(StandardCharsets.UTF_8),
                Map.of("stripe-signature", old)))
                .isInstanceOf(ApiException.class);
    }

    // ---- signing a delivery the way Stripe does ----

    private Optional<PaymentProvider.Confirmation> verify(String body) {
        return provider.verify(body.getBytes(StandardCharsets.UTF_8),
                Map.of("stripe-signature", signature(body, SECRET, Instant.now())));
    }

    private static String event(String id, String type, String object) {
        return """
                {"id": "%s", "object": "event", "type": "%s", "api_version": "2025-01-01",
                 "created": %d, "data": {"object": %s}}"""
                .formatted(id, type, Instant.now().getEpochSecond(), object);
    }

    private static String signature(String body, String secret, Instant at) {
        long timestamp = at.getEpochSecond();
        String signed = timestamp + "." + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of()
                    .formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + v1;
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
