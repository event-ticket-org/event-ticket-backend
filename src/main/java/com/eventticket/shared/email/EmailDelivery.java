package com.eventticket.shared.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One message, and what has happened to it.
 *
 * <p>requirements/006 criterion 8: delivery failure is recorded and retried, never silently
 * swallowed - "a buyer who did not receive their ticket is the failure this system exists to
 * prevent". A row exists before any attempt is made, so a message cannot be lost by the
 * process dying between deciding to send it and sending it.
 *
 * <p>Not tenant-scoped. Half of these are sent before anyone has an Organization at all -
 * email verification is the first thing that happens to a new account.
 */
@Entity
@Table(name = "email_delivery")
public class EmailDelivery {

    public enum Status { PENDING, SENT, FAILED }

    /** Five attempts over roughly twenty minutes, then a row someone has to look at. */
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    protected EmailDelivery() {}

    public EmailDelivery(String recipient, String subject, String body) {
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public UUID id() {
        return id;
    }

    public String recipient() {
        return recipient;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public Status status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public void succeeded() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Backs off exponentially and gives up after {@link #MAX_ATTEMPTS}. Giving up leaves a
     * FAILED row rather than deleting it: the point of this table is that somebody can find
     * out afterwards which buyer never heard from us.
     */
    public void failed(String error) {
        this.attempts++;
        this.lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            this.status = Status.FAILED;
            return;
        }
        this.nextAttemptAt = Instant.now().plus(FIRST_BACKOFF.multipliedBy(1L << (attempts - 1)));
    }
}
