package com.eventticket.platform.support;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Platform administration sits outside the tenant model: an administrator acts on
 * Organizations rather than within one.
 *
 * <p>The flag is read from the database rather than taken from the access token's claim.
 * The claim is there for a client to render with; authority to act on every organization on
 * the platform is worth one extra read.
 */
@Component
public class PlatformAdmins {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdmins.class);

    private final UserDirectory users;

    public PlatformAdmins(UserDirectory users) {
        this.users = users;
    }

    public void requireCallerIsPlatformAdmin() {
        if (!users.isPlatformAdmin(TenantContext.requireUserId())) {
            log.warn("Platform administration refused for non-admin");
            throw ApiException.notPermitted("This action is restricted to platform administrators.");
        }
    }
}
