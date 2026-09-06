package com.eventticket.payment.support;

import com.eventticket.payment.domain.NextAction;
import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The provider the test suite and local development run against, and the reason neither needs
 * a network.
 *
 * <p>It is not a stub. It issues a reference, returns a QR-shaped next action, and verifies a
 * signed callback - so the flow being exercised is the real one, and the parts most likely to
 * be wrong (idempotency, out-of-order delivery, a confirmation arriving after the holds
 * lapsed) can be provoked deliberately instead of waited for.
 *
 * <p>Its signature scheme is the shape every real provider uses: HMAC of the raw body under a
 * shared secret, compared in constant time. Verifying the *raw* bytes rather than a re-encoded
 * object is the part that transfers - a payload parsed and serialised again has a different
 * signature, and the bug only appears in production.
 *
 * <p>Refunds arrive down the same signed channel as payments and say which they are in the
 * body, because that is what real providers do - one endpoint, one secret, a typed event.
 * Taking the kind from the URL instead would make the fake easier and the abstraction wrong.
 */
@Component
public class FakePaymentProvider implements PaymentProvider {

    public static final String NAME = "FAKE";
    public static final String SIGNATURE_HEADER = "x-signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final ObjectMapper json;
    private final String secret;

    public FakePaymentProvider(ObjectMapper json,
                        @Value("${app.payment.fake.secret:fake-provider-shared-secret}") String secret) {
        this.json = json;
        this.secret = secret;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Started start(Attempt attempt) {
        String reference = "ET" + attempt.orderId().toString().substring(0, 8).toUpperCase();
        String providerRef = UUID.randomUUID().toString();

        // A VietQR-shaped payload, because that is the flow this market actually uses and the
        // one a redirect-only abstraction would have failed to model.
        String payload = "%s|%d|%s|%s".formatted(
                providerRef, attempt.total().amount(), attempt.total().currency(), reference);

        return new Started(providerRef, NextAction.displayQr(payload, reference),
                attempt.holdExpiresAt());
    }

    /**
     * A reversal is instant to *start* and settles by callback, exactly as a payment does.
     * Nothing here decides whether the money goes back - the confirmation does, and in
     * development that is a script signing a body.
     */
    @Override
    public Reversal refund(RefundAttempt attempt) {
        return new Reversal("refund-" + UUID.randomUUID());
    }

    @Override
    public Confirmation verify(byte[] rawBody, Map<String, String> headers) {
        String presented = headers.get(SIGNATURE_HEADER);
        if (presented == null || !MessageDigest.isEqual(sign(rawBody), decode(presented))) {
            throw new ApiException(ErrorCodes.NOT_AUTHENTICATED,
                    "The webhook signature did not verify.");
        }
        try {
            JsonNode body = json.readTree(rawBody);
            String status = body.path("status").asString();
            // REFUNDED and REFUND_FAILED are about a refund; PAID and anything else are about
            // a payment. The status carries the kind because a provider's event type is what
            // carries it, and a separate field would be one this fake invented.
            boolean isRefund = status.startsWith("REFUND");
            return new Confirmation(
                    isRefund ? Kind.REFUND : Kind.PAYMENT,
                    body.path("eventId").asString(),
                    body.path("providerRef").asString(),
                    isRefund ? "REFUNDED".equals(status) : "PAID".equals(status),
                    body.path("failureReason").asString(null));
        } catch (tools.jackson.core.JacksonException e) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED, "The webhook body was not readable.");
        }
    }

    /** Exposed so tests can sign a body the way a provider would, rather than skipping the check. */
    public String signatureFor(byte[] rawBody) {
        return HEX.formatHex(sign(rawBody));
    }

    private byte[] sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(rawBody);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Webhook signatures cannot be computed", e);
        }
    }

    private static byte[] decode(String hex) {
        try {
            return HEX.parseHex(hex.trim());
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
