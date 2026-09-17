package com.eventticket.support;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.eventticket.api.model.ScanRequest;
import com.eventticket.api.model.ScanResult;
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.api.model.Ticket;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The suite gets its administrator the way a deployment does. Writing platform_admin
        // with SQL, as this used to, would have kept passing after the configuration that
        // grants it was broken or removed.
        properties = "app.platform-admin-emails=" + ApiTest.PLATFORM_ADMIN_EMAIL)
@Import({TestcontainersConfiguration.class, RecordingEmailSender.Config.class})
public abstract class ApiTest {

    /** Configured as an administrator above, which is the only way to become one. */
    protected static final String PLATFORM_ADMIN_EMAIL = "platform-admin@example.com";

    @Autowired protected RecordingEmailSender email;
    @Autowired protected JdbcTemplate jdbc;

    /** Absent when no search cluster is configured, which is a deployment without one. */
    @Autowired(required = false)
    protected com.eventticket.event.search.outbox.IndexPendingEvents searchIndexer;

    @Autowired(required = false)
    protected com.eventticket.event.search.EventSearchIndex searchIndex;
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
        jdbc.execute("truncate audit_entry, scan, email_delivery, payment_event, payment_session, "
                + "ticket, order_seat, ticket_order, event_seat, event_pricing_tier, event, "
                + "venue, membership, refresh_token, email_verification_token, password_reset_token, "
                + "organization, "
                + "app_user cascade");
        clearSearchIndex();
    }

    /**
     * The index is derived from the database, so it is reset with the database.
     *
     * <p>Leaving it alone was a real failure and an instructive one: the index kept documents
     * for Events that the truncate had removed, a page filled with twenty of those ids, every
     * one of them was dropped by the read-back against Postgres, and the listing came back
     * empty. Three tests that passed alone failed in the suite, and the symptom - an empty
     * listing - pointed nowhere near the cause.
     *
     * <p>It is worth knowing that the same shape exists in a deployment, bounded rather than
     * absent: a document whose Event is gone is filtered out of the results but still occupies
     * a slot on the page, until the nightly rebuild replaces the index wholesale.
     */
    private void clearSearchIndex() {
        if (searchIndex == null) {
            return;
        }
        try {
            searchIndex.replaceAll(java.util.List.of());
        } catch (RuntimeException e) {
            // A cluster that is not up yet is not a reason to fail every test in the class.
            // The tests that need the index will say so themselves.
        }
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

    /** The other decision, for tests that care what a refused Organization is told.
     * requirements/001 criterion 6: a rejection carries a reason, and the reason is readable
     * afterwards rather than only emailed. */
    protected void reject(Organization organization, String reason) {
        var request = new OrganizationDecisionRequest(
                OrganizationDecisionRequest.DecisionEnum.REJECTED);
        request.setReason(reason);
        exchange(HttpMethod.POST, "/admin/organizations/" + organization.getId() + "/decision",
                platformAdmin(), request, Organization.class);
    }

    /** Created on first use and signed in afterwards, so a test may approve more than once. */
    /** Protected since the curated row: a test that places one has to be an administrator. */
    protected TokenPair platformAdmin() {
        String address = PLATFORM_ADMIN_EMAIL;
        ResponseEntity<Void> registered = http.postForEntity("/auth/register",
                new RegisterRequest(address, "correct-horse-battery", "Platform Admin"), Void.class);

        // No SQL and no promotion step: the address is configured, so registering it is
        // enough. That is the mechanism a deployment uses, and this is what proves it works.
        return registered.getStatusCode() == HttpStatus.CONFLICT ? signIn(address) : verify(address);
    }

    private TokenPair verify(String emailAddress) {
        String token = email.verificationTokenFor(emailAddress)
                .orElseThrow(() -> new AssertionError("no verification email was sent to " + emailAddress));
        return http.postForEntity("/auth/verify-email",
                new VerifyEmailRequest(token), TokenPair.class).getBody();
    }

    // --- Venues and events (requirements/002 and 003) --------------------------------------

    /** @param citySlug from {@code GET /public/cities}, not a city's name (requirements/009 criterion 13). */
    protected Venue createVenue(TokenPair session, String name, String citySlug) {
        var input = new VenueInput(name, citySlug, "Asia/Ho_Chi_Minh");
        input.setAddress("14 Cach Mang Thang 8");
        return exchange(HttpMethod.POST, "/venues", session, input, Venue.class).getBody();
    }

    protected ResponseEntity<SeatMap> putSeatMap(TokenPair session, UUID venueId, SeatMap map) {
        return exchange(HttpMethod.PUT, "/venues/" + venueId + "/seat-map", session, map, SeatMap.class);
    }

    /**
     * A real Category rather than the catch-all, so that a fixture nobody thought about does not
     * quietly fill {@code khac} with every Event in the suite - which would make the catch-all
     * useless as a signal and hide a test that meant to land there.
     */
    protected static final String A_CATEGORY = "nhac-song";

    /**
     * With an admission window, because publishing requires one (requirements/003 criterion 16)
     * and almost every test that creates an Event goes on to publish it. A test about the
     * window itself builds its own input.
     */
    protected Event createEvent(TokenPair session, UUID venueId, String title, OffsetDateTime startsAt) {
        return createEvent(session, venueId, title, startsAt,
                startsAt.minusHours(1), startsAt.plusHours(4));
    }

    protected Event createEvent(TokenPair session, UUID venueId, String title,
                                OffsetDateTime startsAt, OffsetDateTime doorsOpenAt,
                                OffsetDateTime endsAt) {
        return createEvent(session, venueId, title, A_CATEGORY, startsAt, doorsOpenAt, endsAt);
    }

    protected Event createEvent(TokenPair session, UUID venueId, String title, String categorySlug,
                                OffsetDateTime startsAt, OffsetDateTime doorsOpenAt,
                                OffsetDateTime endsAt) {
        var input = new EventInput(title, venueId, categorySlug, startsAt);
        input.setDoorsOpenAt(doorsOpenAt);
        input.setEndsAt(endsAt);
        return exchange(HttpMethod.POST, "/events", session, input, Event.class).getBody();
    }

    /** A scan, as the door makes it. */
    protected ScanResult scan(TokenPair session, UUID eventId, String ticketCode, String deviceId) {
        return exchange(HttpMethod.POST, "/events/" + eventId + "/scans", session,
                new ScanRequest(ticketCode, deviceId), ScanResult.class).getBody();
    }

    protected <T> ResponseEntity<T> scanResponse(TokenPair session, UUID eventId, String ticketCode,
                                                 String deviceId, Class<T> responseType) {
        return exchange(HttpMethod.POST, "/events/" + eventId + "/scans", session,
                new ScanRequest(ticketCode, deviceId), responseType);
    }

    /** The current codes for an Order's Tickets, as the buyer's ticket page shows them. */
    protected List<String> ticketCodesOf(TokenPair buyer, UUID orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buyer.getAccessToken());
        List<Ticket> tickets = http.exchange("/orders/" + orderId + "/tickets", HttpMethod.GET,
                new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<List<Ticket>>() {}).getBody();
        return tickets.stream().map(Ticket::getTicketCode).toList();
    }

    protected void priceTier(TokenPair session, UUID eventId, String tierName, long amount) {
        exchange(HttpMethod.PUT, "/events/" + eventId + "/pricing-tiers", session,
                List.of(new PricingTier(tierName, new Money(amount, Money.CurrencyEnum.VND))),
                Object.class);
    }

    /**
     * Publishes, and then makes the search index current before returning.
     *
     * <p>The public listing is served from the index and the index is eventually consistent -
     * a deployment drains the outbox every two seconds, which is under the time it takes
     * somebody to type a query and entirely fine for a visitor. It is not fine for a test,
     * where "publish then list" would be a race, and a test that sometimes sees its own Event
     * is worse than one that never does.
     *
     * <p>So the suite makes the indexer synchronous at the one point every test goes through.
     * That keeps listing assertions about the listing rather than about timing, and the
     * indexer's own behaviour - what it indexes, what it deletes, what happens when the
     * cluster is down - is asserted directly in {@code SearchIndexingTest} instead.
     *
     * <p>A test that changes an Event <em>after</em> publishing and then asserts on the listing
     * calls {@link #indexPendingEvents()} itself. There is no way to make that automatic
     * without putting a drain inside the read, which is the coupling this whole design exists
     * to avoid.
     */
    protected <T> ResponseEntity<T> publish(TokenPair session, UUID eventId, Class<T> responseType) {
        var response = exchange(HttpMethod.POST, "/events/" + eventId + "/publish", session,
                null, responseType);
        indexPendingEvents();
        return response;
    }

    /** Drains the search outbox now, rather than waiting for the schedule the suite turns off. */
    protected void indexPendingEvents() {
        if (searchIndexer != null) {
            searchIndexer.drain();
        }
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
