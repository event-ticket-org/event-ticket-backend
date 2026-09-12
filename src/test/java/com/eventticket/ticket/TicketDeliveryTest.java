package com.eventticket.ticket;

import org.springframework.data.mongodb.core.query.Criteria;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.OrderPage;
import com.eventticket.api.model.Ticket;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.shared.email.DispatchPendingEmails;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Knowledge base requirements/006, and the Ticket Code rules in nfr.md. */
class TicketDeliveryTest extends ApiTest {

    @Autowired private DispatchPendingEmails dispatcher;

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("paying emails a link to the ticket page, not the tickets themselves")
    void confirmationEmailLinksToTheTicketPage() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        email.clear();

        Order paid = buyAndPay(buyer, eventId, seatIdsOf(eventId, 2));

        var messages = email.to("buyer@example.com");
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.subject()).contains("Live in Saigon");
            assertThat(message.body()).contains("/orders/" + paid.getId());
            // An attachment lives in an inbox forever and cannot be withdrawn, which is why
            // requirements/006 sends a link instead.
            assertThat(message.body()).doesNotContain("ET1-");
        });
    }

    @Test
    @DisplayName("the ticket page shows a code per ticket, and the code is not what is stored")
    void ticketCodesAreAssembledNotStored() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order paid = buyAndPay(buyer, eventId, seatIdsOf(eventId, 2));

        List<Ticket> tickets = ticketsOf(buyer, paid.getId());

        assertThat(tickets).hasSize(2);
        assertThat(tickets).allSatisfy(ticket -> {
            assertThat(ticket.getStatus()).isEqualTo(Ticket.StatusEnum.VALID);
            assertThat(ticket.getSeatLabel()).isNotBlank();
            assertThat(ticket.getTicketCode()).startsWith("ET1-");
            assertThat(ticket.getRedeemedAt()).isNull();
        });
        assertThat(tickets).extracting(Ticket::getTicketCode).doesNotHaveDuplicates();

        // nfr.md: a leaked database is not a set of working tickets. Nothing in the row is the
        // code - the stored lookup is a strict prefix component, and the MAC is not there at all.
        List<String> stored = readFields("ticket",
                Criteria.where("orderId").is(paid.getId()), "codeLookup", String.class, null);
        assertThat(stored).noneMatch(lookup ->
                tickets.stream().anyMatch(ticket -> ticket.getTicketCode().equals(lookup)));
        assertThat(tickets).allSatisfy(ticket ->
                assertThat(ticket.getTicketCode().length())
                        .isGreaterThan(stored.get(0).length()));
    }

    @Test
    @DisplayName("someone else's order gives up nothing")
    void ticketsAreVisibleOnlyToTheirBuyer() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order paid = buyAndPay(buyer, eventId, seatIdsOf(eventId, 1));

        TokenPair stranger = signUp("stranger@example.com");
        assertThat(exchange(HttpMethod.GET, "/orders/" + paid.getId() + "/tickets", stranger,
                null, Object.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.GET, "/orders/" + paid.getId(), stranger, null, Object.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a buyer sees their orders from every organization in one place")
    void ordersAcrossOrganizations() {
        UUID acme = publishedEvent();
        UUID rival = publishedEventFor("bob@example.com", "Rival Promotions", "Rival Night");

        TokenPair buyer = signUp("buyer@example.com");
        buyAndPay(buyer, acme, seatIdsOf(acme, 1));
        checkout(buyer, rival, seatIdsOf(rival, 1));

        OrderPage page = exchange(HttpMethod.GET, "/orders", buyer, null, OrderPage.class).getBody();

        assertThat(page.getItems()).hasSize(2);
        assertThat(page.getItems()).extracting(Order::getEventTitle)
                .containsExactlyInAnyOrder("Live in Saigon", "Rival Night");
    }

    @Test
    @DisplayName("a buyer can send the order email to themselves again")
    void resendGoesToTheRecordedAddress() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        Order paid = buyAndPay(buyer, eventId, seatIdsOf(eventId, 1));
        email.clear();

        assertThat(exchange(HttpMethod.POST, "/orders/" + paid.getId() + "/resend-email",
                buyer, null, Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(email.to("buyer@example.com")).singleElement()
                .satisfies(message -> assertThat(message.body())
                        .contains("/orders/" + paid.getId()));
    }

    @Test
    @DisplayName("email that fails to send is recorded and retried, never swallowed")
    void failedDeliveryIsRecordedAndRetried() {
        UUID eventId = publishedEvent();
        TokenPair buyer = signUp("buyer@example.com");
        email.clear();

        // The mail provider is down for the first attempt.
        email.failNext(1);
        Order paid = buyAndPay(buyer, eventId, seatIdsOf(eventId, 1));

        // The buyer heard nothing yet, but the message is owed rather than lost.
        assertThat(email.to("buyer@example.com")).isEmpty();
        assertThat(ticketEmailField("status")).isEqualTo("PENDING");
        assertThat(ticketEmailField("lastError")).contains("bad day");

        // The retry, which the scheduler would run, delivers it.
        setField("emailDelivery", new Criteria(), "nextAttemptAt",
                java.time.Instant.now().minusSeconds(60));
        dispatcher.dispatchDue();

        assertThat(email.to("buyer@example.com")).hasSize(1);
        assertThat(ticketEmailField("status")).isEqualTo("SENT");
        assertThat(paid.getId()).isNotNull();
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<Ticket> ticketsOf(TokenPair session, UUID orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.getAccessToken());
        return http.exchange("/orders/" + orderId + "/tickets", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<Ticket>>() {}).getBody();
    }

    private UUID publishedEvent() {
        return publishedEventFor("alice@example.com", "Acme Events", "Live in Saigon");
    }

    private UUID publishedEventFor(String ownerEmail, String organizationName, String title) {
        TokenPair owner = signUp(ownerEmail);
        var organization = createOrganization(owner, organizationName);
        approve(organization);
        TokenPair manager = switchTo(owner, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(manager, venue.getId(), title, NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);
        publish(manager, event.getId(), Event.class);
        return event.getId();
    }

    /**
     * {@code subject like 'Your tickets%'} as a regex anchored at the start.
     *
     * <p>Anchoring is not cosmetic. An anchored regex can use an index; a leading-wildcard one
     * cannot, exactly as in SQL - so the distinction between {@code like 'x%'} and
     * {@code like '%x%'} survives the migration, it just stops being visible in the syntax.
     */
    private String ticketEmailField(String field) {
        return readFieldWhere("emailDelivery",
                Criteria.where("subject").regex("^Your tickets"), field, String.class);
    }
}
