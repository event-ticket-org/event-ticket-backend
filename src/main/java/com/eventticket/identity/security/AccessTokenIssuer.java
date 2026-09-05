package com.eventticket.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.tenancy.TenantContext;

/**
 * Mints the 15-minute access token of knowledge base ADR-0005.
 *
 * <p>The active Organization is a claim, which is what lets {@code TenantContext} - and
 * therefore row-level security - be driven by the token instead of by a URL path. Fifteen
 * minutes is the revocation window for ordinary endpoints; anything where stale authority
 * would be damaging re-checks against the database, as the scan endpoint will.
 */
@Component
public class AccessTokenIssuer {

    static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
    static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    public static final String ORGANIZATION_CLAIM = "org";
    public static final String PLATFORM_ADMIN_CLAIM = "admin";

    private final JwtEncoder encoder;
    private final String issuer;

    public AccessTokenIssuer(JwtEncoder encoder, @Value("${app.security.issuer}") String issuer) {
        this.encoder = encoder;
        this.issuer = issuer;
    }

    public String issue(AppUser user, UUID activeOrganizationId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_LIFETIME))
                .subject(user.id().toString())
                .claim(PLATFORM_ADMIN_CLAIM, user.platformAdmin());

        // Omitted rather than set to null: JwtClaimsSet rejects null values, and an absent
        // claim is the honest representation of "signed in, no organization chosen yet".
        if (activeOrganizationId != null) {
            claims.claim(ORGANIZATION_CLAIM, activeOrganizationId.toString());
        }

        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(() -> "HS256").build(), claims.build()))
                .getTokenValue();
    }

    public static long accessTokenSeconds() {
        return ACCESS_TOKEN_LIFETIME.toSeconds();
    }
}
