package com.eventticket.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.eventticket.organization.domain.Membership;

/**
 * A refresh token, held server-side so that it can be revoked. This table is what makes
 * knowledge base ADR-0005 true rather than aspirational: removing a Membership or changing a
 * Role takes effect at refresh, because refresh re-reads authority from the database instead
 * of trusting the expiring token's claims.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshTokenRecord {

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
