package com.eventticket.identity.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(Logout.class);

    private final RefreshTokenRepository refreshTokens;

    public Logout(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public void logout() {
        UUID userId = TenantContext.requireUserId();
        refreshTokens.revokeAllFor(userId, Instant.now());
        log.info("Signed out, all refresh tokens revoked userId={}", userId);
    }
}
