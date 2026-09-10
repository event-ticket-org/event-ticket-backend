package com.eventticket.shared.tenancy;

import java.util.UUID;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;

/**
 * The tenant and user for the current request, established by {@code JwtTenantFilter} from
 * the access token's claims and read by {@link TenantScope} when each query is built.
 *
 * <p>Under Postgres this was read once per transaction and pushed into the database session,
 * which then applied it to everything. It is now read once per query, by whichever criteria
 * the collection's old policy corresponded to - the same value, consulted far more often, and
 * by code that has to remember to ask.
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

}
