package com.eventticket.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.TestcontainersConfiguration;
import com.eventticket.api.model.CheckoutRequest;
import com.eventticket.api.model.CreateOrganizationRequest;
import com.eventticket.api.model.EventSeatMap;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.PaymentSession;
import com.eventticket.api.model.StartPaymentRequest;
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
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.VerifyEmailRequest;
import com.eventticket.payment.support.FakePaymentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
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
    @Autowired protected FakePaymentProvider fakeProvider;

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
        jdbc.execute("truncate audit_entry, email_delivery, payment_event, payment_session, "
                + "ticket, order_seat, ticket_order, event_seat, event_pricing_tier, event, "
                + "venue, membership, refresh_token, email_verification_token, organization, "
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
        exchange(HttpMethod.POST, "/admin/organizations/" + organization.getId() + "/decision",
                platformAdmin(), new OrganizationDecisionRequest(
                        OrganizationDecisionRequest.DecisionEnum.APPROVED), Organization.class);
    }

    /** Created on first use and signed in afterwards, so a test may approve more than once. */
    private TokenPair platformAdmin() {
        String address = "platform-admin@example.com";
        ResponseEntity<Void> registered = http.postForEntity("/auth/register",
                new RegisterRequest(address, "correct-horse-battery", "Platform Admin"), Void.class);

        TokenPair session = registered.getStatusCode() == HttpStatus.CONFLICT
                ? signIn(address)
                : verify(address);
        makePlatformAdmin(address);
        return session;
    }

    private TokenPair verify(String emailAddress) {
        String token = email.verificationTokenFor(emailAddress)
                .orElseThrow(() -> new AssertionError("no verification email was sent to " + emailAddress));
        return http.postForEntity("/auth/verify-email",
                new VerifyEmailRequest(token), TokenPair.class).getBody();
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

    protected void priceTier(TokenPair session, UUID eventId, String tierName, long amount) {
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

    // --- Buying, paying and tickets (requirements/004-006) ---------------------------------

    protected ResponseEntity<Order> checkout(TokenPair session, UUID eventId, List<UUID> seatIds) {
        return exchange(HttpMethod.POST, "/checkout", session,
                new CheckoutRequest(eventId, seatIds), Order.class);
    }

    protected EventSeatMap publicSeatMap(UUID eventId) {
        return exchange(HttpMethod.GET, "/public/events/" + eventId + "/seat-map", null, null,
                EventSeatMap.class).getBody();
    }

    /** Seats nobody has taken yet, so a second buyer in the same test is not handed sold ones. */
    protected List<UUID> seatIdsOf(UUID eventId, int count) {
        List<UUID> free = publicSeatMap(eventId).getSeats().stream()
                .filter(seat -> seat.getAvailability() == SeatAvailability.AVAILABLE)
                .map(com.eventticket.api.model.EventSeat::getId).limit(count).toList();
        assertThat(free).as("available seats for event " + eventId).hasSize(count);
        return free;
    }

    protected PaymentSession startPayment(TokenPair session, UUID orderId) {
        return exchange(HttpMethod.POST, "/orders/" + orderId + "/payment-sessions", session,
                new StartPaymentRequest("FAKE"), PaymentSession.class).getBody();
    }

    /**
     * Delivers a webhook the way the provider would: a raw body, signed. Tests never call
     * ConfirmPayment directly, because the signature check and the raw-body handling are two
     * of the things most likely to be wrong.
     */
    protected ResponseEntity<String> deliverWebhook(String eventId, String providerRef, String status) {
        String body = """
                {"eventId":"%s","providerRef":"%s","status":"%s"}""".formatted(eventId, providerRef, status);
        return deliverWebhookRaw(body, fakeProvider.signatureFor(body.getBytes(StandardCharsets.UTF_8)));
    }

    protected ResponseEntity<String> deliverWebhookRaw(String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Signature", signature);
        return http.exchange("/webhooks/payments/fake", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** Buys and pays for seats end to end, returning the paid Order. */
    protected Order buyAndPay(TokenPair buyer, UUID eventId, List<UUID> seatIds) {
        Order order = checkout(buyer, eventId, seatIds).getBody();
        PaymentSession session = startPayment(buyer, order.getId());
        deliverWebhook(UUID.randomUUID().toString(), providerRefOf(session), "PAID");
        return exchange(HttpMethod.GET, "/orders/" + order.getId(), buyer, null, Order.class).getBody();
    }

    /** The provider's own handle for an attempt, which a confirmation names. */
    protected String providerRefOf(PaymentSession session) {
        return jdbc.queryForObject("select provider_ref from payment_session where id = ?",
                String.class, session.getId());
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
