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
import com.eventticket.api.model.ScanRequest;
import com.eventticket.api.model.ScanResult;
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.api.model.Ticket;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.VerifyEmailRequest;
import com.eventticket.payment.support.FakePaymentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    @Autowired protected MongoTemplate mongo;
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
        // Every collection, dropped. `truncate ... cascade` named eighteen tables in dependency
        // order because a foreign key would otherwise refuse the statement - the ordering was
        // doing real work, and forgetting a table was a compile-time-ish error you found at
        // once. Nothing here depends on order, because nothing here refers to anything, and a
        // collection left out of this list simply leaks state into the next test.
        //
        // Asking the database which collections exist, rather than listing them, for exactly
        // that reason. The old list had already drifted: `refund` was missing from it and
        // survived only by cascading from ticket_order.
        mongo.getCollectionNames().stream()
                .filter(name -> !name.startsWith("system."))
                .filter(name -> !name.equals("mongockChangeLog") && !name.equals("mongockLock"))
                .forEach(name -> mongo.remove(new Query(), name));
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
    private TokenPair platformAdmin() {
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

    protected Venue createVenue(TokenPair session, String name, String city) {
        var input = new VenueInput(name, city, "Asia/Ho_Chi_Minh");
        input.setAddress("14 Cach Mang Thang 8");
        return exchange(HttpMethod.POST, "/venues", session, input, Venue.class).getBody();
    }

    protected ResponseEntity<SeatMap> putSeatMap(TokenPair session, UUID venueId, SeatMap map) {
        return exchange(HttpMethod.PUT, "/venues/" + venueId + "/seat-map", session, map, SeatMap.class);
    }

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
        var input = new EventInput(title, venueId, startsAt);
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

    // ---------------------------------------------------------------------------------
    // Reaching past the API, the MongoDB way.
    //
    // Sixty-one tests drove the application over HTTP and then checked the database directly,
    // or shifted a clock in it to make an expiry testable. That is legitimate - a hold that
    // lapses in ten minutes cannot be waited out - but it is also the coupling that made those
    // tests a rewrite rather than a recompile.
    //
    // These helpers exist so the rewrite happened once, here, instead of at forty call sites.
    // Worth noticing what is missing: every SQL statement they replace named columns, and
    // Postgres refused the statement if a column did not exist. A misspelt field name below
    // matches nothing and the assertion simply reads zero.
    // ---------------------------------------------------------------------------------

    protected long countIn(String collection) {
        return mongo.count(new Query(), collection);
    }

    protected long countIn(String collection, Criteria criteria) {
        return mongo.count(new Query(criteria), collection);
    }

    protected <T> T readField(String collection, Object id, String field, Class<T> type) {
        return readFieldWhere(collection, Criteria.where("_id").is(id), field, type);
    }

    protected <T> T readFieldWhere(String collection, Criteria criteria, String field, Class<T> type) {
        org.bson.Document found = mongo.findOne(new Query(criteria), org.bson.Document.class, collection);
        return found == null ? null : type.cast(found.get(field));
    }

    protected <T> List<T> readFields(String collection, String field, Class<T> type) {
        return readFields(collection, new Criteria(), field, type, null);
    }

    protected <T> List<T> readFields(String collection, Criteria criteria, String field,
                                     Class<T> type, String sortBy) {
        Query query = new Query(criteria);
        if (sortBy != null) {
            query.with(org.springframework.data.domain.Sort.by(sortBy));
        }
        return mongo.find(query, org.bson.Document.class, collection).stream()
                .map(document -> type.cast(document.get(field)))
                .toList();
    }

    protected long setField(String collection, Criteria criteria, String field, Object value) {
        return mongo.updateMulti(new Query(criteria),
                new org.springframework.data.mongodb.core.query.Update().set(field, value),
                collection).getModifiedCount();
    }

    /** Makes a hold lapse without waiting ten minutes for it. */
    protected void expireHoldsOf(UUID orderId) {
        setField("eventSeat", Criteria.where("heldByOrderId").is(orderId),
                "heldUntil", Instant.now().minusSeconds(60));
        setField("ticketOrder", Criteria.where("_id").is(orderId),
                "holdExpiresAt", Instant.now().minusSeconds(60));
    }

    /** The provider's own handle for an attempt, which a confirmation names. */
    protected String providerRefOf(PaymentSession session) {
        org.bson.Document found = mongo.findOne(
                new Query(Criteria.where("_id").is(session.getId())), org.bson.Document.class,
                "paymentSession");
        return found == null ? null : found.getString("providerRef");
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
