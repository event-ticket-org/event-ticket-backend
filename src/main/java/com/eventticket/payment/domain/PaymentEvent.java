package com.eventticket.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One confirmation, seen once.
 *
 * <p>requirements/005 criterion 4 is idempotency, and this row is how it is enforced: the
 * unique key on (provider, providerEventId) means a second delivery cannot commit alongside
 * the first. Looking for a previous delivery and then handling this one would lose the race
 * it exists to win - webhooks are delivered concurrently, not merely twice.
 *
 * <p>Written last in the transaction, on purpose. If the work fails, this row is not committed
 * either, and the provider's retry is processed rather than mistaken for a duplicate.
 */
@Entity
@Table(name = "payment_event")
public class PaymentEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "session_id")
    private UUID sessionId;

    /** A delivery settles one flow or the other. Exactly one of these two is set. */
    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected PaymentEvent() {}

    public PaymentEvent(String provider, String providerEventId, UUID sessionId) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.sessionId = sessionId;
    }

    /**
     * The same row for the other flow. One table, because idempotency is about the delivery
     * and a provider numbers both flows from the same sequence - two tables would let one
     * event id be processed once as a payment and again as a refund.
     */
    public static PaymentEvent forRefund(String provider, String providerEventId, UUID refundId) {
        PaymentEvent event = new PaymentEvent();
        event.provider = provider;
        event.providerEventId = providerEventId;
        event.refundId = refundId;
        return event;
    }
}
