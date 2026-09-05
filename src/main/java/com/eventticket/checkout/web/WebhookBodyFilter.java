package com.eventticket.checkout.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Keeps the exact bytes of a webhook body available after Spring has parsed it.
 *
 * <p>A signature covers what the provider sent, byte for byte. Verifying a payload that has
 * been deserialised and serialised again verifies something else - key order, whitespace and
 * number formatting all differ - and the mismatch appears only against a real provider, never
 * against a test that round-trips through the same library.
 *
 * <p>Scoped to the webhook paths. Buffering every request body would be a memory cost paid on
 * every upload for the benefit of two endpoints.
 */
@Component
public class WebhookBodyFilter extends OncePerRequestFilter {

    /**
     * Spring 7 requires a cache limit, and one is right regardless: a webhook body is a few
     * hundred bytes, so anything past this is not a payload worth buffering to verify.
     */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().contains("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new ContentCachingRequestWrapper(request, MAX_BODY_BYTES), response);
    }
}
