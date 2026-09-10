package com.eventticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every credential is unset unless something sets it.
 *
 * <p>This reads the configuration as text rather than booting anything, because what it is
 * defending is a property of the file: a credential written {@code ${JWT_SECRET:a-value}}
 * works everywhere and is therefore never noticed, and the deployment that inherits the
 * fallback is the one nobody looks at. Ticket codes are a random lookup plus a MAC under
 * {@code app.tickets.keys}, so a deployment still holding the development key issues tickets
 * that anybody with this repository can mint - and it would look, from the outside and from
 * the logs, exactly like a working system.
 *
 * <p>No test asserts that the dev values are present, because the suite already does: these
 * run on the dev profile, and a missing one fails every {@code @SpringBootTest} at startup.
 */
class DeploymentConfigurationTest {

    /** The environment variables that carry a credential, and must therefore have no default. */
    private static final List<String> CREDENTIALS = List.of(
            "JWT_SECRET", "STORAGE_ACCESS_KEY", "STORAGE_SECRET_KEY",
            "TICKET_CODE_KEY", "FAKE_PAYMENT_SECRET");

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    @Test
    @DisplayName("no credential has a fallback, so an unconfigured deployment cannot start")
    void everyCredentialIsRequired() throws IOException {
        String configuration = Files.readString(APPLICATION_YML);

        for (String variable : CREDENTIALS) {
            Matcher placeholder = Pattern.compile("\\$\\{" + variable + "(:[^}]*)?}")
                    .matcher(configuration);

            assertThat(placeholder.find())
                    .as("%s is read by application.yml", variable)
                    .isTrue();
            assertThat(placeholder.group(1))
                    .as("%s has a default, so a deployment that never sets it starts anyway "
                            + "using a value published in this repository", variable)
                    .isNull();
        }
    }

    /**
     * The other half of the same rule. Stripe's keys are absent rather than required - no key
     * means no Stripe provider at all, which is what lets a clone and the whole suite run with
     * no account - and an empty default is how that is written.
     *
     * <p>Mail is the same shape and the same reason. Requiring {@code MAIL_PASSWORD} would stop
     * an application that never intended to send any, and the absent case is a real deployment
     * rather than a broken one: it writes to the log, and says so at startup.
     */
    @Test
    @DisplayName("an optional provider is absent by default, not required")
    void optionalProvidersStayOptional() throws IOException {
        assertThat(Files.readString(APPLICATION_YML))
                .contains("${STRIPE_SECRET_KEY:}")
                .contains("${STRIPE_WEBHOOK_SECRET:}")
                .contains("${MAIL_HOST:}")
                .contains("${MAIL_PASSWORD:}");
    }

    /**
     * An unreachable mail server is not an outage, and an empty {@code MAIL_HOST} must not
     * refuse to boot the deployment.
     *
     * <p>Boot's mail health indicator opens an SMTP connection, and Boot treats an empty
     * {@code spring.mail.host} as present - so the ordinary compose case, a declared
     * {@code MAIL_HOST} with nothing in the environment, aims a sender at localhost:587 and
     * reports the application DOWN. {@code depends_on: service_healthy} then stops the
     * frontend ever starting: a whole deployment refusing to come up over a variable that
     * means "we do not send mail". Found on a server, exactly that way.
     */
    @Test
    @DisplayName("mail is not a liveness condition")
    void mailHealthIsDisabled() throws IOException {
        assertThat(Files.readString(APPLICATION_YML))
                .containsPattern("health:\\s*\\n\\s*mail:[\\s\\S]*?enabled:\\s*false");
    }

    /**
     * {@code DATABASE_PASSWORD} left this list with Postgres, and the rule it belonged to does
     * not port cleanly - which is worth a test of its own rather than a silent deletion.
     *
     * <p>Postgres took its password as a separate property, so "no credential has a default" was
     * exact: {@code password: ${DATABASE_PASSWORD}} with no fallback, and an unconfigured
     * deployment refused to boot. MongoDB folds the credential into the connection string, and
     * that string also carries the host and the database name - so it needs a default to be
     * usable on a laptop, and a default URI is a default that quietly means "no authentication".
     *
     * <p>So the rule weakens from "cannot start unconfigured" to "cannot start unconfigured with
     * a credential nobody chose". That is genuinely less than Postgres gave, and this asserts the
     * part that survives: the default may exist, and it may not contain credentials. A URI of the
     * form {@code mongodb://user:pass@host} in this file would be a published password.
     */
    @Test
    @DisplayName("the MongoDB URI has a default, and that default carries no credentials")
    void theConnectionStringDefaultIsUnauthenticated() throws IOException {
        String configuration = Files.readString(APPLICATION_YML);

        Matcher uri = Pattern.compile("\\$\\{MONGODB_URI(:[^}]*)?}").matcher(configuration);
        assertThat(uri.find()).as("application.yml reads MONGODB_URI").isTrue();

        String fallback = uri.group(1);
        assertThat(fallback)
                .as("the URI needs a default so a clone runs with no setup")
                .isNotNull();
        assertThat(fallback)
                .as("a default connection string containing '@' is a published credential")
                .doesNotContain("@");
    }
}
