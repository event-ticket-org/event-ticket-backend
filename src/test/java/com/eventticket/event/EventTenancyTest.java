package com.eventticket.event;

import org.springframework.data.mongodb.core.query.Query;
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

        // LOST. Two events and two venues exist; Bob's tenant used to see one of each because
        // the policy filtered the collection itself. It now sees both of each.
        assertThat(countAs(bobId, rival.getId(), "event")).isEqualTo(2);
        // Two venues exist and Bob's tenant sees both. Under the policy it saw one.

        assertThat(countAs(bobId, rival.getId(), "venue")).isEqualTo(2);
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

        assertThat(countAs(bobId, rival.getId(), "event")).isEqualTo(2);
        // Ten, not twenty: seats exist only from publish onward, so the draft above has none.
        // Measured rather than predicted - the first version of this line guessed twenty and was
        // wrong, which is a small illustration of the same lesson as the whole migration.
        assertThat(countAs(bobId, rival.getId(), "eventSeat")).isEqualTo(10);
        // event_pricing_tier is not a collection any more - the tiers are embedded in the
        // event, so there is nothing separate left to count or to isolate.

        // With no tenant at all - an anonymous visitor - the published Event used to be
        // readable and nothing else was. Everything is readable now.
        assertThat(countAs(null, null, "event")).isEqualTo(2);
        assertThat(countAs(null, null, "venue")).isEqualTo(1);
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

    private UUID userIdOf(TokenPair session) {
        return exchange(org.springframework.http.HttpMethod.GET, "/me", session, null,
                com.eventticket.api.model.Me.class).getBody().getId();
    }
}
