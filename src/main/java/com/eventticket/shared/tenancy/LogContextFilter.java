package com.eventticket.shared.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a request id into the logging context, and returns it to the caller.
 *
 * <p>Every log line for one request carries the same id, so a user reporting "it failed at
 * 2am" can be traced through registration, tenant adoption and the failure itself without
 * guessing which lines belong together. The response header is what lets them give you the
 * id in the first place.
 *
 * <p>Named for the log context rather than the request: Spring Boot already registers a
 * bean called {@code requestContextFilter}, and a second one of that name stops the
 * application from starting.
 *
 * <p>Runs before authentication, because a request that fails to authenticate still needs to
 * be traceable. {@code JwtTenantFilter} adds the user and organization once they are known.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String ORGANIZATION_ID = "organizationId";

    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled: an id left behind would label the next request's lines.
            MDC.clear();
        }
    }
}
