package com.eventticket.identity;

import com.eventticket.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the authenticated identity out of the access token into {@link TenantContext}, from
 * where {@code TenantAwareTransactionManager} publishes it to Postgres for every transaction.
 *
 * <p>The context is cleared in a finally block without exception. Threads are pooled, and a
 * tenant left behind on one would be handed to the next request that borrowed the thread -
 * a cross-tenant data leak produced by omission rather than by any query being wrong.
 */
@Component
class JwtTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                TenantContext.set(subjectOf(jwt), organizationOf(jwt));
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
