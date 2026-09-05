package com.eventticket.checkout;

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
        assertThat(countAs(firstId, null, "select count(*) from ticket_order")).isEqualTo(1L);
        assertThat(countAs(firstId, null, "select count(*) from order_seat")).isEqualTo(2L);
        assertThat(countAs(firstId, null, "select count(*) from ticket")).isEqualTo(2L);

        assertThat(countAs(secondId, null, "select count(*) from ticket_order")).isEqualTo(1L);
        assertThat(countAs(secondId, null, "select count(*) from ticket")).isZero();
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
        assertThat(countAs(null, organizationOf(acme), "select count(*) from ticket_order")).isEqualTo(1L);
        assertThat(countAs(null, organizationOf(acme), "select count(*) from ticket")).isEqualTo(2L);
        assertThat(countAs(null, organizationOf(rival), "select count(*) from ticket")).isEqualTo(3L);

        // ...and the buyer, who is a member of neither, sees both of their own.
        assertThat(countAs(userIdOf(buyer), null, "select count(*) from ticket_order")).isEqualTo(2L);
    }

    private long countAs(UUID userId, UUID organizationId, String sql) {
        TenantContext.set(userId, organizationId);
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> jdbc.queryForObject(sql, Long.class));
        } finally {
            TenantContext.clear();
        }
    }

    private UUID organizationOf(UUID eventId) {
        return jdbc.queryForObject("select organization_id from event where id = ?", UUID.class, eventId);
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
