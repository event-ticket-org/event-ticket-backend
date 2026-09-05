package com.eventticket.identity.security;

import com.eventticket.shared.tenancy.LogContextFilter;
import com.eventticket.shared.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.eventticket.shared.tenancy.TenantAwareTransactionManager;

/**
 * Copies the authenticated identity out of the access token into {@link TenantContext}, from
 * where {@code TenantAwareTransactionManager} publishes it to Postgres for every transaction.
 *
 * <p>The context is cleared in a finally block without exception. Threads are pooled, and a
 * tenant left behind on one would be handed to the next request that borrowed the thread -
 * a cross-tenant data leak produced by omission rather than by any query being wrong.
 */
@Component
public class JwtTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                UUID userId = subjectOf(jwt);
                UUID organizationId = organizationOf(jwt);
                TenantContext.set(userId, organizationId);

                // Every log line from here on names who and which tenant, so use cases can
                // log what happened without repeating identifiers in each message.
                if (userId != null) {
                    MDC.put(LogContextFilter.USER_ID, userId.toString());
                }
                if (organizationId != null) {
                    MDC.put(LogContextFilter.ORGANIZATION_ID, organizationId.toString());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static UUID subjectOf(Jwt jwt) {
        String subject = jwt.getSubject();
        return subject == null ? null : UUID.fromString(subject);
    }

    private static UUID organizationOf(Jwt jwt) {
        String organizationId = jwt.getClaimAsString(AccessTokenIssuer.ORGANIZATION_CLAIM);
        return organizationId == null || organizationId.isBlank() ? null : UUID.fromString(organizationId);
    }
}
