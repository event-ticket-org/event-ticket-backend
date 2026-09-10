package com.eventticket.identity.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.UUID;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;

/**
 * A person with an account. One identity for everyone: buying tickets and working for an
 * Organization are things a User does, not different kinds of account (knowledge base
 * ADR-0005 and CONTEXT.md).
 */
@Document(collection = "appUser")
public class AppUser {

    @Id
    private UUID id = UUID.randomUUID();

    private String email;

    private String displayName;

    private String passwordHash;

    private boolean emailVerified;

    private boolean platformAdmin;

    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public AppUser(String email, String displayName, String passwordHash) {
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

    public void setPassword(String displayName, String passwordHash) {
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

    public String passwordHash() {
        return passwordHash;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public boolean platformAdmin() {
        return platformAdmin;
    }

    /**
     * Granted from configuration only (see {@code ConfiguredPlatformAdmins}); there is deliberately no
     * request that can confer it.
     */
    public void promoteToPlatformAdmin() {
        this.platformAdmin = true;
    }

    /**
     * requirements/001 criterion 18. Distinct from {@link #setPassword} because that one is
     * an invited User completing registration and takes the display name they are choosing at
     * the same time; this one is somebody who already has both and is replacing one of them.
     */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
    }
}
