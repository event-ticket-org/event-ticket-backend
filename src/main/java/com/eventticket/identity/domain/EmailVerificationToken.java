package com.eventticket.identity.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.UUID;

@Document(collection = "emailVerificationToken")
public class EmailVerificationToken {

    @Id
    private String tokenHash;

    private UUID userId;

    private Instant expiresAt;

    private Instant consumedAt;

    protected EmailVerificationToken() {}

    public EmailVerificationToken(String tokenHash, UUID userId, Instant expiresAt) {
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
