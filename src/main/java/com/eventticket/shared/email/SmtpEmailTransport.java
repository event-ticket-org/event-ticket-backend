package com.eventticket.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Hands a message to a real mail server.
 *
 * <p>The one transport that leaves the building. Everything about queuing, retrying and
 * recording a failure is already in {@link OutboxEmailSender} and {@link DispatchPendingEmails}
 * and none of it belongs here: this is one attempt, and its whole contract is to throw when the
 * attempt did not happen.
 *
 * <p>{@code SimpleMailMessage} rather than a MIME builder, because every message this system
 * sends is plain text (requirements/006). An HTML body would be a product decision - what a
 * ticket looks like in an inbox - rather than a transport one, and it would arrive here as a
 * second method rather than as a flag.
 *
 * <p>The From address is separate from the login. They are the same thing on a personal Gmail
 * account, which is exactly why conflating them is easy: a provider that permits sending as an
 * alias, or an account whose login is not an address at all, breaks the moment the two are
 * assumed equal. It defaults to the username because that is the common case and a default
 * nobody has to think about is the point of a default.
 */
public class SmtpEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailTransport.class);

    private final JavaMailSender sender;
    private final String fromAddress;

    public SmtpEmailTransport(JavaMailSender sender, String fromAddress) {
        this.sender = sender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void deliver(String toAddress, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toAddress);
        message.setSubject(subject);
        message.setText(body);

        try {
            sender.send(message);
        } catch (MailException e) {
            // Rethrown, not swallowed. The caller records the failure on the row and schedules
            // another attempt; a transport that returned quietly would leave a message marked
            // delivered that never was, which is the failure this whole class exists to end.
            throw new IllegalStateException("SMTP delivery to " + toAddress + " failed", e);
        }
        // The address, never the body. A verification link and a Ticket Code both travel in a
        // body, and a log line carrying either is a credential written to disk (nfr.md).
        log.info("Email delivered to={} subject={}", toAddress, subject);
    }
}
