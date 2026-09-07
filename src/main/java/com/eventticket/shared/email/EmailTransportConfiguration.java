package com.eventticket.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * Which transport this deployment has, decided in one place.
 *
 * <p>The same shape as the Stripe provider and for the same reason: no mail server configured
 * means there is no mail server, rather than one that fails on first use. A fresh clone and the
 * whole suite run with no account, and {@code MAIL_HOST} is the single switch - set it and mail
 * leaves the building, leave it unset and it goes to the log as it always did.
 *
 * <p>One {@code @Bean} method with an {@code if} rather than two conditional components. The
 * conditional forms read as though they are equivalent and are not:
 * {@code @ConditionalOnMissingBean} on a scanned {@code @Component} depends on the order beans
 * happen to be defined in, which is how a fallback silently wins over the real thing. Here there
 * is one bean, one decision, and nothing about ordering to get right.
 *
 * <p>The host is read as a property rather than off {@code MailProperties}, which lives in
 * Boot's auto-configuration and changed package between Boot 3 and Boot 4. The property name is
 * the published contract; the class holding it is not. Binding to the class bought nothing and
 * broke on the first compile against 4.1.
 *
 * <p>{@code ObjectProvider} because {@code JavaMailSender} genuinely may not exist: Boot creates
 * one only when {@code spring.mail.host} is set, so asking for it directly would make a mail
 * server mandatory for every deployment - the opposite of what this class is for.
 */
@Configuration
public class EmailTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmailTransportConfiguration.class);

    @Bean
    public EmailTransport emailTransport(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${app.email.from:}") String configuredFrom) {

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || !StringUtils.hasText(host)) {
            // Said out loud at startup, because the failure it precedes is silent: every
            // delivery is recorded SENT and nobody receives anything, so a deployment that
            // meant to configure mail and mistyped the variable looks exactly like one that
            // is working.
            log.warn("No mail server configured (MAIL_HOST is unset) - email will be written "
                    + "to this log and delivered to nobody");
            return new LoggingEmailTransport();
        }

        String from = StringUtils.hasText(configuredFrom) ? configuredFrom : username;
        log.info("Mail transport is SMTP host={} from={}", host, from);
        return new SmtpEmailTransport(sender, from);
    }
}
