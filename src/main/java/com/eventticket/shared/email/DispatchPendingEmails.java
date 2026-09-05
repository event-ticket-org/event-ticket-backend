package com.eventticket.shared.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retry half of requirements/006 criterion 8.
 *
 * <p>Every attempt runs in its own transaction, so one message that cannot be delivered does
 * not roll back the record of the others having been. A failure is recorded on the row -
 * attempt count, the error, when to try again - and after five attempts the row is left
 * FAILED rather than deleted, because the value of this table is being able to answer "which
 * buyer never heard from us".
 */
@Component
public class DispatchPendingEmails {

    private static final Logger log = LoggerFactory.getLogger(DispatchPendingEmails.class);
    private static final int BATCH = 50;

    private final EmailDeliveryRepository deliveries;
    private final EmailTransport transport;

    public DispatchPendingEmails(EmailDeliveryRepository deliveries, EmailTransport transport) {
        this.deliveries = deliveries;
        this.transport = transport;
    }

    /** The immediate attempt, made just after the transaction that queued the message. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(UUID id) {
        deliveries.findById(id).ifPresent(this::attempt);
    }

    @Scheduled(fixedDelayString = "${app.email.retry-interval:PT1M}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchDue() {
        List<EmailDelivery> due = deliveries.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                EmailDelivery.Status.PENDING, Instant.now(), PageRequest.ofSize(BATCH));
        due.forEach(this::attempt);
    }

    private void attempt(EmailDelivery delivery) {
        if (delivery.status() != EmailDelivery.Status.PENDING) {
            return;
        }
        try {
            transport.deliver(delivery.recipient(), delivery.subject(), delivery.body());
            delivery.succeeded();
        } catch (RuntimeException e) {
            delivery.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Email delivery failed emailId={} attempt={} status={}",
                    delivery.id(), delivery.attempts(), delivery.status());
        }
        deliveries.save(delivery);
    }
}
