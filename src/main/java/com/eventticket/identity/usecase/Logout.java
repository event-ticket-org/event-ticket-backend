package com.eventticket.identity.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.repository.RefreshTokenRepository;

/**
 * Revokes every refresh token the User holds, not merely the one presented. Signing out on a
 * lost phone should end the session on the lost phone, and the client cannot present a token
 * it no longer has.
 */
@Component
public class Logout {

    private final RefreshTokenRepository refreshTokens;

    public Logout(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public void logout() {
        refreshTokens.revokeAllFor(TenantContext.requireUserId(), Instant.now());
    }
}
