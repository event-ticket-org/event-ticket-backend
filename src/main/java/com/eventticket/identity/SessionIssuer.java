package com.eventticket.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Issues a token pair and persists the refresh half.
 *
 * <p>Four use cases sign a User in - verifying an email, logging in, refreshing, and
 * switching organization - and all four need this. It is a collaborator rather than a helper
 * shared between use cases: it owns the refresh token's storage, which is state no use case
 * owns. That is the distinction ADR-0001 draws between pushing logic down and passing it
 * sideways.
 */
@Component
class SessionIssuer {

    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenRepository refreshTokens;

    SessionIssuer(AccessTokenIssuer accessTokens, RefreshTokenRepository refreshTokens) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    Session issueFor(AppUser user, UUID activeOrganizationId) {
        String accessToken = accessTokens.issue(user, activeOrganizationId);

        String refreshToken = SecureTokens.issue();
        refreshTokens.save(new RefreshTokenRecord(
                SecureTokens.hash(refreshToken),
                user.id(),
                activeOrganizationId,
                Instant.now().plus(AccessTokenIssuer.REFRESH_TOKEN_LIFETIME)));

        return new Session(accessToken, refreshToken,
                AccessTokenIssuer.accessTokenSeconds(), activeOrganizationId);
    }
}
