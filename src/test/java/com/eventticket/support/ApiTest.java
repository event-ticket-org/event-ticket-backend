package com.eventticket.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.TestcontainersConfiguration;
import com.eventticket.api.model.CreateOrganizationRequest;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.EventInput;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.Money;
import com.eventticket.api.model.OrganizationStatus;
import com.eventticket.api.model.PricingTier;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.Venue;
import com.eventticket.api.model.VenueInput;
import com.eventticket.api.model.LoginRequest;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.OrganizationDecisionRequest;
import com.eventticket.api.model.SwitchOrganizationRequest;
import com.eventticket.api.model.RegisterRequest;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.VerifyEmailRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        jdbc.execute("truncate audit_entry, event_seat, event_pricing_tier, event, venue, "
                + "membership, refresh_token, email_verification_token, organization, "
                + "app_user cascade");
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

    /** Signing in again is what puts the chosen Organization into the access token. */
    protected TokenPair switchTo(TokenPair session, Organization organization) {
        return exchange(HttpMethod.POST, "/auth/switch-organization", session,
                new SwitchOrganizationRequest(organization.getId()), TokenPair.class).getBody();
    }

    /**
     * Takes a new Organization through platform approval, which publishing requires
     * (requirements/003 criterion 5). The administrator is created on demand, because most
     * tests care that the Organization is approved and not who approved it.
     */
    protected void approve(Organization organization) {
        TokenPair admin = signUp("platform-admin@example.com");
        makePlatformAdmin("platform-admin@example.com");
        exchange(HttpMethod.POST, "/admin/organizations/" + organization.getId() + "/decision",
                admin, new OrganizationDecisionRequest(
                        OrganizationDecisionRequest.DecisionEnum.APPROVED), Organization.class);
    }

    protected void makePlatformAdmin(String emailAddress) {
        jdbc.update("update app_user set platform_admin = true where lower(email) = lower(?)", emailAddress);
    }

    // --- Venues and events (requirements/002 and 003) --------------------------------------

    protected Venue createVenue(TokenPair session, String name, String city) {
        var input = new VenueInput(name, city, "Asia/Ho_Chi_Minh");
        input.setAddress("14 Cach Mang Thang 8");
        return exchange(HttpMethod.POST, "/venues", session, input, Venue.class).getBody();
    }

    protected ResponseEntity<SeatMap> putSeatMap(TokenPair session, UUID venueId, SeatMap map) {
        return exchange(HttpMethod.PUT, "/venues/" + venueId + "/seat-map", session, map, SeatMap.class);
    }

    protected Event createEvent(TokenPair session, UUID venueId, String title, OffsetDateTime startsAt) {
        return exchange(HttpMethod.POST, "/events", session,
                new EventInput(title, venueId, startsAt), Event.class).getBody();
    }

    protected void priceTier(TokenPair session, UUID eventId, String tierName, int amount) {
        exchange(HttpMethod.PUT, "/events/" + eventId + "/pricing-tiers", session,
                List.of(new PricingTier(tierName, new Money(amount, Money.CurrencyEnum.VND))),
                Object.class);
    }

    protected <T> ResponseEntity<T> publish(TokenPair session, UUID eventId, Class<T> responseType) {
        return exchange(HttpMethod.POST, "/events/" + eventId + "/publish", session, null, responseType);
    }

    /** The single Organization the session's user belongs to, as the API reports it. */
    protected Organization onlyOrganizationOf(TokenPair session) {
        var membership = exchange(HttpMethod.GET, "/me", session, null, Me.class)
                .getBody().getMemberships().get(0);
        return new Organization(membership.getOrganizationId(), membership.getOrganizationName(),
                OrganizationStatus.PENDING_APPROVAL);
    }

    protected <T> ResponseEntity<T> exchange(HttpMethod method, String path, TokenPair session,
                                             Object body, Class<T> responseType) {
        return exchange(method, path, session, body, responseType, Map.of());
    }

    /**
     * Query values go through the URI factory as template variables. Interpolating them into
     * the path by hand gets them encoded twice, and a city with a space in it then matches
     * nothing.
     */
    protected <T> ResponseEntity<T> exchange(HttpMethod method, String path, TokenPair session,
                                             Object body, Class<T> responseType,
                                             Map<String, ?> uriVariables) {
        HttpHeaders headers = new HttpHeaders();
        if (session != null) {
            headers.setBearerAuth(session.getAccessToken());
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), responseType, uriVariables);
    }

    private static String nameFrom(String emailAddress) {
        return emailAddress.substring(0, emailAddress.indexOf('@'));
    }
}
