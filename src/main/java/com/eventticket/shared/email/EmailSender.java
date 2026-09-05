package com.eventticket.shared.email;

/**
 * Sending email, behind an interface so that neither the test suite nor local development
 * depends on a mail provider (knowledge base requirements/006 criterion 7).
 *
 * <p>The production implementation is AWS SES. Unlike the payment providers, email providers
 * do not differ in flow, so this interface is deliberately thin - there is no reason to model
 * a session or a next action here.
 */
public interface EmailSender {

    public void send(String toAddress, String subject, String body);
}
