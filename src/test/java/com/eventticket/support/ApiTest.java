package com.eventticket.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.TestcontainersConfiguration;
import com.eventticket.api.model.CreateOrganizationRequest;
import com.eventticket.api.model.LoginRequest;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.RegisterRequest;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.VerifyEmailRequest;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Base for tests that drive the application over HTTP against real Postgres.
 *
 * <p>Deliberately end-to-end rather than mocked. The rules this slice implements - row-level
 * security, the last-owner constraint, revocation at refresh - are enforced by Postgres and
 * by the security filter chain, and a test with a mocked repository would assert none of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, RecordingEmailSender.Config.class})
public abstract class ApiTest {

    @Autowired protected RecordingEmailSender email;
    @Autowired protected JdbcTemplate jdbc;

    @LocalServerPort private int port;

    /**
     * Spring Boot 4 no longer ships TestRestTemplate, so this is a plain RestTemplate with the
     * base URL bound and error responses left alone - these tests assert on 4xx bodies, and a
     * client that throws on them would hide exactly what is being checked.
     */
    protected RestTemplate http;

    @BeforeEach
    void prepareClient() {
        // The JDK client rather than the default: HttpURLConnection cannot issue PATCH, which
        // the contract uses for changing a member's role.
        http = new RestTemplate(new JdkClientHttpRequestFactory());
        http.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port + "/api/v1"));
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
    }

    @BeforeEach
    void resetState() {
        email.clear();
        // Order matters: memberships reference both sides.
        jdbc.execute("truncate audit_entry, membership, refresh_token, "
                + "email_verification_token, organization, app_user cascade");
    }

    /** Registers, follows the emailed verification link, and returns a signed-in session. */
    protected TokenPair signUp(String emailAddress) {
        ResponseEntity<Void> registered = http.postForEntity("/auth/register",
                new RegisterRequest(emailAddress, "correct-horse-battery", nameFrom(emailAddress)),
                Void.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String token = email.verificationTokenFor(emailAddress)
                .orElseThrow(() -> new AssertionError("no verification email was sent to " + emailAddress));

        ResponseEntity<TokenPair> verified = http.postForEntity("/auth/verify-email",
                new VerifyEmailRequest(token), TokenPair.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        return verified.getBody();
    }

    protected TokenPair signIn(String emailAddress) {
        return http.postForEntity("/auth/login",
                new LoginRequest(emailAddress, "correct-horse-battery"), TokenPair.class).getBody();
    }

    protected Organization createOrganization(TokenPair session, String name) {
        return exchange(HttpMethod.POST, "/organizations", session,
                new CreateOrganizationRequest(name), Organization.class).getBody();
    }

    protected void makePlatformAdmin(String emailAddress) {
        jdbc.update("update app_user set platform_admin = true where lower(email) = lower(?)", emailAddress);
    }

    protected <T> ResponseEntity<T> exchange(HttpMethod method, String path, TokenPair session,
                                             Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (session != null) {
            headers.setBearerAuth(session.getAccessToken());
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }

    private static String nameFrom(String emailAddress) {
        return emailAddress.substring(0, emailAddress.indexOf('@'));
    }
}
