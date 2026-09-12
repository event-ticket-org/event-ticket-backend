package com.eventticket.shared.tenancy;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adopts a tenant that the request could not carry: a use case establishing who the caller is
 * as part of its own work - token refresh authenticates from a refresh token and only then
 * knows the User - or one acting on an Organization from outside it, as platform approval does.
 *
 * <p><strong>This class used to have a reason to exist and now barely does, which is worth
 * recording.</strong> Under Postgres, setting the ThreadLocal was not enough: the transaction
 * had already begun and had already told the database an empty tenant, and the policies read
 * the database setting rather than the JVM. So this opened the live connection and re-issued
 * {@code set_config} against it.
 *
 * <p>Nothing reads a database setting now. The ThreadLocal <em>is</em> the tenant, because
 * {@link TenantScope} composes its criteria from it at query time, so adopting one is an
 * assignment. The whole hazard - a JVM and a database disagreeing about who the caller is -
 * cannot arise when only one of them has an opinion.
 *
 * <p>Kept as a named component rather than inlined, because the narrowness was always the
 * point: if this appears in an ordinary use case, the tenant should have come from the access
 * token and that use case is doing authentication it has no business doing.
 */
@Component
public class TenantPublisher {

    public void adopt(UUID userId, UUID organizationId) {
        TenantContext.set(userId, organizationId);
    }
}
