package com.eventticket.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * requirements/001 criterion 21: expires, one use, and superseded by the next request.
 *
 * <p>Deliberately a separate entity from {@link EmailVerificationToken} rather than a shared
 * superclass with a discriminator. They are the same four columns today and are not the same
 * thing: this one opens an account that already exists and already holds orders, which is why
 * its lifetime is an hour against a day, and why a new request ends the old ones. Folding them
 * together would put both sets of rules in one place and make the difference a field.
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected PasswordResetToken() {}

    public PasswordResetToken(String tokenHash, UUID userId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public UUID userId() {
        return userId;
    }

    public boolean isUsable(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
