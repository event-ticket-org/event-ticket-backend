package com.eventticket.checkout.web;

import com.eventticket.api.WebhooksApi;
import com.eventticket.checkout.usecase.ConfirmPayment;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

/**
 * The one endpoint that can mark an Order paid.
 *
 * <p>Almost everything is a 204. requirements/005 criterion 6 asks for that explicitly - an
 * unknown or already-settled session is acknowledged and ignored - and the reason is
 * operational: a provider that gets an error retries, so answering "I could not use this"
 * with a failure turns a stale delivery into an indefinite loop. Only a signature that does
 * not verify is refused, and that is a 401 because it is the one case where the sender is
 * not who they claim to be.
 *
 * <p>The parsed {@code payload} argument is ignored. It exists because the contract declares
 * it, and taking it keeps the generated interface satisfied, but the bytes are what get
 * verified - see {@link WebhookBodyFilter}.
 */
@RestController
public class PaymentWebhookController implements WebhooksApi {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final ConfirmPayment confirmPayment;

    public PaymentWebhookController(ConfirmPayment confirmPayment) {
        this.confirmPayment = confirmPayment;
    }

    @Override
    public ResponseEntity<Void> webhooksPaymentsProviderPost(String provider, Map<String, Object> payload) {
        HttpServletRequest request = currentRequest();
        try {
            ConfirmPayment.Outcome outcome = confirmPayment.confirm(
                    provider.toUpperCase(Locale.ROOT), rawBodyOf(request), headersOf(request));
            log.debug("Webhook handled provider={} outcome={}", provider, outcome);
        } catch (DataIntegrityViolationException e) {
            // Two deliveries of the same confirmation arrived at once. Both did the work; this
            // one lost the unique key on payment_event and its transaction rolled back, which
            // is exactly the intended outcome. The provider is told it was received.
            log.info("Concurrent duplicate webhook provider={} rolled back", provider);
        }
        return ResponseEntity.noContent().build();
    }

    private static byte[] rawBodyOf(HttpServletRequest request) {
        ContentCachingRequestWrapper cached =
                WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        return cached == null ? new byte[0] : cached.getContentAsByteArray();
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        return headers;
    }

    private static HttpServletRequest currentRequest() {
        var attributes = (org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        return attributes.getRequest();
    }
}
