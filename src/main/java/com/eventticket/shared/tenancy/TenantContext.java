package com.eventticket.shared.tenancy;

import java.util.UUID;
import com.eventticket.identity.security.JwtTenantFilter;
import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;

/**
 * The tenant and user for the current request, established by {@code JwtTenantFilter} from
 * the access token's claims and read by {@link TenantAwareTransactionManager} when a
 * transaction begins.
 *
 * <p>Never populated from a URL path or a request parameter. The active Organization is a
 * token claim precisely so that passing someone else's identifier cannot change it
 * (knowledge base ADR-0004).
 */
public final class TenantContext {

    private record Current(UUID userId, UUID organizationId) {}

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID userId, UUID organizationId) {
        CURRENT.set(new Current(userId, organizationId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static UUID userId() {
        Current c = CURRENT.get();
        return c == null ? null : c.userId();
    }

    public static UUID organizationId() {
        Current c = CURRENT.get();
        return c == null ? null : c.organizationId();
    }

    /**
     * The active Organization, or a failure if there is none. Use where a use case genuinely
     * requires a tenant, so that the absence of one is an error here rather than an empty
     * result set later.
     */
    public static UUID requireOrganizationId() {
        UUID id = organizationId();
        if (id == null) {
            throw new ApiException(ErrorCodes.NOT_PERMITTED, "No active organization for this request.");
        }
        return id;
    }

    public static UUID requireUserId() {
        UUID id = userId();
        if (id == null) {
            throw new ApiException(ErrorCodes.NOT_AUTHENTICATED, "Not signed in.");
        }
        return id;
    }

    public static String userIdAsSetting() {
        UUID id = userId();
        return id == null ? "" : id.toString();
    }

    public static String organizationIdAsSetting() {
        UUID id = organizationId();
        return id == null ? "" : id.toString();
    }
}
