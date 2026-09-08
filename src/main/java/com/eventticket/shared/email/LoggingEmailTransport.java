package com.eventticket.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes email to the log instead of sending it. The default in development, where the
 * verification link appearing in the console is exactly what you want.
 *
 * <p>Chosen by {@link EmailTransportConfiguration} rather than scanned, because it is a
 * fallback and a fallback that can win a tie is not one. Its javadoc used to say SES replaced
 * it in deployed environments; nothing did, so every deployment wrote its mail to a log and
 * recorded it delivered.
 */
public class LoggingEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailTransport.class);

    @Override
    public void deliver(String toAddress, String subject, String body) {
        log.info("EMAIL to={} subject={}\n{}", toAddress, subject, body);
    }
}
