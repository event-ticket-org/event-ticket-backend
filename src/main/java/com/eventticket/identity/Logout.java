package com.eventticket.identity;

import com.eventticket.shared.TenantContext;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every refresh token the User holds, not merely the one presented. Signing out on a
 * lost phone should end the session on the lost phone, and the client cannot present a token
 * it no longer has.
 */
@Component
class Logout {

    private final RefreshTokenRepository refreshTokens;

    Logout(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    void logout() {
        refreshTokens.revokeAllFor(TenantContext.requireUserId(), Instant.now());
    }
}
