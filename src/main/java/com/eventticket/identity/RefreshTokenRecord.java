package com.eventticket.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token, held server-side so that it can be revoked. This table is what makes
 * knowledge base ADR-0005 true rather than aspirational: removing a Membership or changing a
 * Role takes effect at refresh, because refresh re-reads authority from the database instead
 * of trusting the expiring token's claims.
 */
@Entity
@Table(name = "refresh_token")
class RefreshTokenRecord {

    @Id
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "active_organization_id")
    private UUID activeOrganizationId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshTokenRecord() {}

    RefreshTokenRecord(String tokenHash, UUID userId, UUID activeOrganizationId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.activeOrganizationId = activeOrganizationId;
        this.expiresAt = expiresAt;
    }

    UUID userId() {
        return userId;
    }

    UUID activeOrganizationId() {
        return activeOrganizationId;
    }

    boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    void revoke(Instant now) {
        this.revokedAt = now;
    }
}
