package com.eventticket.shared.email;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records the message, then tries to deliver it.
 *
 * <p>In that order, and the order is the point. The row is written inside the caller's
 * transaction, so an Order that commits has an email row committed with it - the process can
 * die immediately afterwards and the message is still owed rather than forgotten. Delivery is
 * attempted after that transaction commits, because handing a message to a mail provider
 * inside a database transaction means either sending mail for work that then rolls back, or
 * holding a transaction open across a network call.
 *
 * <p>The immediate attempt is for latency, not for correctness. {@link DispatchPendingEmails}
 * would pick the row up anyway; a buyer waiting for a verification link should not have to
 * wait for a scheduler tick.
 */
@Component
public class OutboxEmailSender implements EmailSender {

    private final EmailDeliveryRepository deliveries;
    private final DispatchPendingEmails dispatcher;

    public OutboxEmailSender(EmailDeliveryRepository deliveries, DispatchPendingEmails dispatcher) {
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
    }

    @Override
    public void send(String toAddress, String subject, String body) {
        UUID id = deliveries.save(new EmailDelivery(toAddress, subject, body)).id();

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatcher.dispatch(id);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatcher.dispatch(id);
            }
        });
    }
}
