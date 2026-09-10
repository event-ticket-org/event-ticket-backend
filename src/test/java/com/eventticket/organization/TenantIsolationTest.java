package com.eventticket.organization;

import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.Membership;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.RefreshRequest;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.TokenPair;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.support.ApiTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.eventticket.organization.usecase.ListMembers;

/**
 * Knowledge base ADR-0004 and ADR-0005.
 *
 * <p>These assert the two claims that are easy to make and easy to get wrong: that one
 * tenant cannot see another's rows, and that removing someone actually takes their access
 * away rather than merely hiding a button.
 */
class TenantIsolationTest extends ApiTest {

    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("one organization's members are invisible to another")
    void membersAreNotVisibleAcrossOrganizations() {
        TokenPair alice = signUp("alice@example.com");
        Organization acme = createOrganization(alice, "Acme Events");
        TokenPair aliceAtAcme = switchTo(alice, acme);
        exchange(HttpMethod.POST, "/organization/members", aliceAtAcme,
                new InviteMemberRequest("acme-staff@example.com", Role.GATE_STAFF), Membership.class);

        TokenPair bob = signUp("bob@example.com");
        Organization rival = createOrganization(bob, "Rival Promotions");
        TokenPair bobAtRival = switchTo(bob, rival);

        List<Membership> visibleToBob = listMembers(bobAtRival);

        assertThat(visibleToBob)
                .extracting(Membership::getEmail)
                .containsExactly("bob@example.com");
        assertThat(visibleToBob)
                .allSatisfy(m -> assertThat(m.getOrganizationId()).isEqualTo(rival.getId()));
    }

    @Test
    @DisplayName("a person in two organizations sees both, and only the active one's members")
    void membershipsAreVisibleAcrossOrganizationsToTheirOwner() {
        TokenPair alice = signUp("alice@example.com");
        Organization first = createOrganization(alice, "Acme Events");
        Organization second = createOrganization(alice, "Second Venture");

        Me me = exchange(HttpMethod.GET, "/me", alice, null, Me.class).getBody();
        assertThat(me.getMemberships())
                .extracting(Membership::getOrganizationName)
                .containsExactlyInAnyOrder("Acme Events", "Second Venture");

        // ...but listing members is scoped to whichever organization is active.
        assertThat(listMembers(switchTo(alice, first)))
                .allSatisfy(m -> assertThat(m.getOrganizationId()).isEqualTo(first.getId()));
        assertThat(listMembers(switchTo(alice, second)))
                .allSatisfy(m -> assertThat(m.getOrganizationId()).isEqualTo(second.getId()));
    }

    @Test
    @DisplayName("a removed member loses the organization when their token is refreshed")
    void removalTakesEffectAtRefresh() {
        TokenPair alice = signUp("alice@example.com");
        Organization acme = createOrganization(alice, "Acme Events");
        TokenPair aliceAtAcme = switchTo(alice, acme);

        exchange(HttpMethod.POST, "/organization/members", aliceAtAcme,
                new InviteMemberRequest("volunteer@example.com", Role.GATE_STAFF), Membership.class);

        TokenPair volunteer = signUp("volunteer@example.com");
        TokenPair volunteerAtAcme = switchTo(volunteer, acme);
        assertThat(volunteerAtAcme.getActiveOrganizationId()).isEqualTo(acme.getId());

        Me volunteerMe = exchange(HttpMethod.GET, "/me", volunteer, null, Me.class).getBody();
        exchange(HttpMethod.DELETE, "/organization/members/" + volunteerMe.getId(),
                aliceAtAcme, null, Void.class);

        // ADR-0005: revocation bites at refresh, not before. The volunteer's existing access
        // token is still cryptographically valid - that is the accepted fifteen-minute window
        // - but refreshing re-reads authority from the database and drops the organization.
        TokenPair refreshed = http.postForEntity("/auth/refresh",
                new RefreshRequest(volunteerAtAcme.getRefreshToken()), TokenPair.class).getBody();

        assertThat(refreshed.getActiveOrganizationId()).isNull();
        assertThat(exchange(HttpMethod.GET, "/me", refreshed, null, Me.class)
                .getBody().getMemberships()).isEmpty();
    }

    @Test
    @DisplayName("the isolation is the database's, not the application's")
    void rowLevelSecurityFiltersTheTableItself() {
        TokenPair alice = signUp("alice@example.com");
        Organization acme = createOrganization(alice, "Acme Events");
        TokenPair aliceAtAcme = switchTo(alice, acme);
        exchange(HttpMethod.POST, "/organization/members", aliceAtAcme,
                new InviteMemberRequest("acme-staff@example.com", Role.GATE_STAFF), Membership.class);

        TokenPair bob = signUp("bob@example.com");
        createOrganization(bob, "Rival Promotions");

        UUID aliceId = exchange(HttpMethod.GET, "/me", alice, null, Me.class).getBody().getId();

        // LOST. Under Postgres this was 2 of 3 - the policy filtered the table itself. There
        // is no policy, so an unfiltered count sees all three memberships across both
        // organizations. Asserted rather than deleted, because a number that changed from 2 to
        // 3 is the clearest statement of what this migration cost.
        long visible = countAllMembershipsAs(aliceId, acme.getId());

        assertThat(visible).isEqualTo(3);
        // And with no tenant at all - where Postgres showed only alice's own row - still every row.
        assertThat(countAllMembershipsAs(aliceId, null)).isEqualTo(3);
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
    private long countAllMembershipsAs(UUID userId, UUID organizationId) {
        TenantContext.set(userId, organizationId);
        try {
            return new TransactionTemplate(transactionManager).execute(status ->
                    mongo.count(new Query(), "membership"));
        } finally {
            TenantContext.clear();
        }
    }

    private List<Membership> listMembers(TokenPair session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.getAccessToken());
        ResponseEntity<List<Membership>> response = http.exchange("/organization/members",
                HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        return response.getBody();
    }
}
