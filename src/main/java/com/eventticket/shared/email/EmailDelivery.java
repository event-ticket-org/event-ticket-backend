package com.eventticket.shared.email;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
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
@Document(collection = "emailDelivery")
public class EmailDelivery {

    public enum Status { PENDING, SENT, FAILED }

    /** Five attempts over roughly twenty minutes, then a row someone has to look at. */
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);

    @Id
    private UUID id = UUID.randomUUID();

    private String recipient;

    private String subject;

    private String body;

    private Status status = Status.PENDING;

    private int attempts;

    private String lastError;

    private Instant nextAttemptAt = Instant.now();

    private Instant createdAt = Instant.now();

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
