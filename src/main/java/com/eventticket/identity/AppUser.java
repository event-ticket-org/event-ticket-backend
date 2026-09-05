package com.eventticket.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person with an account. One identity for everyone: buying tickets and working for an
 * Organization are things a User does, not different kinds of account (knowledge base
 * ADR-0005 and CONTEXT.md).
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    AppUser(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    /**
     * A User created by an invitation, before the person has registered. It exists so the
     * Membership has something to attach to; it cannot sign in until a password is set,
     * because {@code matches} is never reached with a null hash.
     */
    public static AppUser invited(String email) {
        return new AppUser(email, email, null);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    void setPassword(String displayName, String passwordHash) {
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    String passwordHash() {
        return passwordHash;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public boolean platformAdmin() {
        return platformAdmin;
    }

    void markEmailVerified() {
        this.emailVerified = true;
    }
}
