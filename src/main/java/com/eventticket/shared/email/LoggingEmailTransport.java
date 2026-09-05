package com.eventticket.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes email to the log instead of sending it. The default in development, where the
 * verification link appearing in the console is exactly what you want. AWS SES replaces it in
 * deployed environments; tests supply their own.
 */
@Component
public class LoggingEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailTransport.class);

    @Override
    public void deliver(String toAddress, String subject, String body) {
        log.info("EMAIL to={} subject={}\n{}", toAddress, subject, body);
    }
}
