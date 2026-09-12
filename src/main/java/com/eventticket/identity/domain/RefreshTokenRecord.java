package com.eventticket.identity.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.UUID;
import com.eventticket.organization.domain.Membership;

/**
 * A refresh token, held server-side so that it can be revoked. This table is what makes
 * knowledge base ADR-0005 true rather than aspirational: removing a Membership or changing a
 * Role takes effect at refresh, because refresh re-reads authority from the database instead
 * of trusting the expiring token's claims.
 */
@Document(collection = "refreshToken")
public class RefreshTokenRecord {

    @Id
    private String tokenHash;

    private UUID userId;

    private UUID activeOrganizationId;

    private Instant expiresAt;

    private Instant revokedAt;

    protected RefreshTokenRecord() {}

    public RefreshTokenRecord(String tokenHash, UUID userId, UUID activeOrganizationId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.activeOrganizationId = activeOrganizationId;
        this.expiresAt = expiresAt;
    }

    public UUID userId() {
        return userId;
    }

    public UUID activeOrganizationId() {
        return activeOrganizationId;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
