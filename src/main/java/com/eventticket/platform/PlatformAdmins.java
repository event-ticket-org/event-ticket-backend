package com.eventticket.platform;

import com.eventticket.shared.ApiException;
import com.eventticket.shared.TenantContext;
import com.eventticket.shared.UserDirectory;
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
class PlatformAdmins {

    private final UserDirectory users;

    PlatformAdmins(UserDirectory users) {
        this.users = users;
    }

    void requireCallerIsPlatformAdmin() {
        if (!users.isPlatformAdmin(TenantContext.requireUserId())) {
            throw ApiException.notPermitted("This action is restricted to platform administrators.");
        }
    }
}
