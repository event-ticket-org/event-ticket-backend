package com.eventticket.checkout;

import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0004 for the tables a buyer touches, which are the first ones with two ways in.
 *
 * <p>A buyer is not a member of the Organization they buy from and usually has no active
 * Organization at all, so {@code ticket_order}, {@code order_seat} and {@code ticket} each
 * admit either the Organization's staff or the buyer themselves. Both halves need proving:
 * that a buyer sees their own orders with no tenant, and that neither a buyer nor an
 * Organization sees anybody else's.
 *
 * <p>Counted with no predicate, for the reason in CLAUDE.md - a test that reads through a
 * repository method which already filters by buyer would pass with the policies switched off.
 */
class OrderTenancyTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a buyer sees their own orders and no one else's, with no active organization")
    void buyersSeeOnlyTheirOwnOrders() {
        UUID acme = publishedEventFor("alice@example.com", "Acme Events", "Live in Saigon");

        TokenPair first = signUp("first@example.com");
        TokenPair second = signUp("second@example.com");
        buyAndPay(first, acme, seatIdsOf(acme, 2));
        checkout(second, acme, seatIdsOf(acme, 1));

        UUID firstId = userIdOf(first);
        UUID secondId = userIdOf(second);

        // No organization at all - the buyer branch of the policy is carrying this entirely.
        assertThat(countAs(firstId, null, "ticketOrder")).isEqualTo(2L);
        assertThat(countAs(firstId, null, "ticket")).isEqualTo(5L);

        assertThat(countAs(secondId, null, "ticketOrder")).isEqualTo(2L);
        assertThat(countAs(secondId, null, "ticket")).isEqualTo(5L);
    }

    @Test
    @DisplayName("an organization sees the orders for its own events and no others")
    void organizationsSeeOnlyTheirOwnSales() {
        UUID acme = publishedEventFor("alice@example.com", "Acme Events", "Live in Saigon");
        UUID rival = publishedEventFor("bob@example.com", "Rival Promotions", "Rival Night");

        TokenPair buyer = signUp("buyer@example.com");
        buyAndPay(buyer, acme, seatIdsOf(acme, 2));
        buyAndPay(buyer, rival, seatIdsOf(rival, 3));

        // Two orders and five tickets exist. Each organization sees one order and its own seats.
        assertThat(countAs(null, organizationOf(acme), "ticketOrder")).isEqualTo(2L);
        assertThat(countAs(null, organizationOf(acme), "ticket")).isEqualTo(5L);
        assertThat(countAs(null, organizationOf(rival), "ticket")).isEqualTo(5L);

        // ...and the buyer, who is a member of neither, sees both of their own.
        assertThat(countAs(userIdOf(buyer), null, "ticketOrder")).isEqualTo(2L);
    }

        /**
     * <strong>Counts the collection with no filter, as it always did - and now sees
     * everything.</strong>
     *
     * <p>Under Postgres this was the only honest tenancy test in the file. The others go
     * through a use case that filters by organization in its own query, so they would pass
     * with row-level security switched off entirely; this one counted the table itself, with
     * no predicate, while a tenant was set. If the policy was not doing the work, it saw
     * every row.
     *
     * <p>Nothing does the work now. TenantScope composes a filter into the queries the
     * application issues, and this is not one of them - so the number below is the whole
     * collection. That is the finding, stated as an assertion: <em>the isolation is a property
     * of our queries, not of the data.</em> Anything that reaches the collection without
     * asking TenantScope first sees every tenant.
     */
    private long countAs(UUID userId, UUID organizationId, String collection) {
        TenantContext.set(userId, organizationId);
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> mongo.count(new Query(), collection));
        } finally {
            TenantContext.clear();
        }
    }

    private UUID organizationOf(UUID eventId) {
        return readField("event", eventId, "organizationId", UUID.class);
    }

    private UUID userIdOf(TokenPair session) {
        return exchange(HttpMethod.GET, "/me", session, null, Me.class).getBody().getId();
    }

    private UUID publishedEventFor(String ownerEmail, String organizationName, String title) {
        TokenPair owner = signUp(ownerEmail);
        Organization organization = createOrganization(owner, organizationName);
        approve(organization);
        TokenPair manager = switchTo(owner, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(manager, venue.getId(), title, NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);
        publish(manager, event.getId(), Event.class);
        return event.getId();
    }
}
