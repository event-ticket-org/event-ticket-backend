package com.eventticket.shared.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which transport a deployment gets, and why that is worth a test.
 *
 * <p>The bug this replaces was not a wrong answer, it was a silent one: {@code LoggingEmailTransport}
 * was the only implementation and an unconditional component, so every deployment wrote its mail
 * to a log file and recorded every delivery {@code SENT}. Nobody could verify an address, and
 * nothing anywhere said so.
 *
 * <p>So the assertion is about the type of the bean, not about a message arriving. Delivery
 * itself is the mail server's job and a test that mocked one would only prove the mock was
 * called; what can actually go wrong here is the wiring, and the wiring is what this pins.
 */
class EmailTransportConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(EmailTransportConfiguration.class);

    @Test
    @DisplayName("no mail host means the log, which is what a laptop and the suite want")
    void withoutAMailHostItLogs() {
        context.run(started -> assertThat(started).getBean(EmailTransport.class)
                .isInstanceOf(LoggingEmailTransport.class));
    }

    @Test
    @DisplayName("a mail host means SMTP")
    void withAMailHostItSends() {
        context.withPropertyValues("spring.mail.host=smtp.example.com")
                .run(started -> assertThat(started).getBean(EmailTransport.class)
                        .isInstanceOf(SmtpEmailTransport.class));
    }

    /**
     * An empty variable is what an unset one becomes here. Every mail property is written
     * {@code ${MAIL_HOST:}}, so a deployment that declares the variable and leaves it blank -
     * a compose file with {@code MAIL_HOST: ${MAIL_HOST}} and nothing in the environment - hands
     * this an empty string rather than nothing at all. Treating that as "configured" would build
     * an SMTP transport pointed at no host and fail on the first person who registered.
     */
    @Test
    @DisplayName("an empty mail host is an absent one")
    void anEmptyHostIsNotAHost() {
        context.withPropertyValues("spring.mail.host=")
                .run(started -> assertThat(started).getBean(EmailTransport.class)
                        .isInstanceOf(LoggingEmailTransport.class));
    }

    /**
     * Mail is not a liveness condition, and this is the property that says so.
     *
     * <p>The mail starter brings a health indicator that opens an SMTP connection, and Boot
     * treats an empty {@code spring.mail.host} as *present* - so a compose file declaring
     * {@code MAIL_HOST} with nothing in the environment gets a sender aimed at localhost:587
     * and an application reporting DOWN while working perfectly. {@code depends_on:
     * service_healthy} then keeps the frontend from starting at all, which is how this was
     * found: on a server, with the whole deployment refusing to come up.
     *
     * <p>This pins the mechanism - that the property removes the bean. That
     * {@code application.yml} actually sets it is pinned by {@code DeploymentConfigurationTest},
     * which is where the file's own guarantees live.
     */
    @Test
    @DisplayName("the disabling property removes the indicator, even with a host configured")
    void mailIsNotALivenessCondition() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MailSenderAutoConfiguration.class,
                        MailHealthContributorAutoConfiguration.class))
                .withPropertyValues(
                        "spring.mail.host=smtp.example.com",
                        "management.health.mail.enabled=false")
                .run(started -> assertThat(started).doesNotHaveBean(MailHealthIndicator.class));
    }
}
