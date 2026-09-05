package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0004 for the tables added by requirements/002 and 003, with the one deliberate
 * exception this slice introduces: a published Event is public, and so are the rows a buyer
 * needs in order to read it.
 *
 * <p>Counted with no predicate at all, for the reason in CLAUDE.md: a test that reads through
 * a repository method which filters by organization passes with row-level security switched
 * off entirely.
 */
class EventTenancyTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("another organization's draft event and its venue are invisible")
    void draftsAreInvisibleAcrossOrganizations() {
        TokenPair alice = signUp("alice@example.com");
        Organization acme = createOrganization(alice, "Acme Events");
        TokenPair aliceAtAcme = switchTo(alice, acme);
        Venue acmeVenue = createVenue(aliceAtAcme, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(aliceAtAcme, acmeVenue.getId(), SeatMaps.block("Standard", 2, 2));
        createEvent(aliceAtAcme, acmeVenue.getId(), "Acme's Secret Show", NEXT_MONTH);

        TokenPair bob = signUp("bob@example.com");
        Organization rival = createOrganization(bob, "Rival Promotions");
        TokenPair bobAtRival = switchTo(bob, rival);
        Venue rivalVenue = createVenue(bobAtRival, "Rival Hall", "Da Nang");
        createEvent(bobAtRival, rivalVenue.getId(), "Rival's Show", NEXT_MONTH);

        UUID bobId = userIdOf(bob);

        // Two events and two venues exist. Bob's tenant sees one of each, and the count is
        // taken from the table itself rather than through a query that filters.
        assertThat(countAs(bobId, rival.getId(), "select count(*) from event")).isEqualTo(1);
        assertThat(countAs(bobId, rival.getId(), "select count(*) from venue")).isEqualTo(1);
    }

    @Test
    @DisplayName("a published event is visible to everyone, and that is the exception on purpose")
    void publishedEventsAreDeliberatelyPublic() {
        TokenPair alice = signUp("alice@example.com");
        Organization acme = createOrganization(alice, "Acme Events");
        approve(acme);
        TokenPair aliceAtAcme = switchTo(alice, acme);
        Venue venue = createVenue(aliceAtAcme, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(aliceAtAcme, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(aliceAtAcme, venue.getId(), "Live in Saigon", NEXT_MONTH);
        priceTier(aliceAtAcme, event.getId(), "Standard", 250_000);
        publish(aliceAtAcme, event.getId(), Event.class);

        // A draft of the same organization, which must stay private.
        createEvent(aliceAtAcme, venue.getId(), "Still Cooking", NEXT_MONTH);

        TokenPair bob = signUp("bob@example.com");
        Organization rival = createOrganization(bob, "Rival Promotions");
        UUID bobId = userIdOf(bob);

        assertThat(countAs(bobId, rival.getId(), "select count(*) from event")).isEqualTo(1);
        assertThat(countAs(bobId, rival.getId(), "select count(*) from event_seat")).isEqualTo(10);
        assertThat(countAs(bobId, rival.getId(), "select count(*) from event_pricing_tier")).isEqualTo(1);

        // With no tenant at all - an anonymous buyer following a link - the same rows and no
        // others are readable.
        assertThat(countAs(null, null, "select count(*) from event")).isEqualTo(1);
        assertThat(countAs(null, null, "select count(*) from venue")).isEqualTo(1);
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

    private UUID userIdOf(TokenPair session) {
        return exchange(org.springframework.http.HttpMethod.GET, "/me", session, null,
                com.eventticket.api.model.Me.class).getBody().getId();
    }
}
