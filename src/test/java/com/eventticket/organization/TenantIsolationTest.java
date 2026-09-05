package com.eventticket.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.Membership;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.RefreshRequest;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.SwitchOrganizationRequest;
import com.eventticket.api.model.TokenPair;
import com.eventticket.shared.TenantContext;
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

        // The other tests go through ListMembers, which also filters by organization in its
        // own query - so they would pass even with row-level security switched off. This one
        // counts the whole table with no predicate at all. If the policy is not doing the
        // work, this sees all three memberships.
        long visible = countAllMembershipsAs(aliceId, acme.getId());

        assertThat(visible).isEqualTo(2);
        assertThat(countAllMembershipsAs(aliceId, null)).isEqualTo(1); // only alice's own rows
    }

    private long countAllMembershipsAs(UUID userId, UUID organizationId) {
        TenantContext.set(userId, organizationId);
        try {
            return new TransactionTemplate(transactionManager).execute(status ->
                    jdbc.queryForObject("select count(*) from membership", Long.class));
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

    private TokenPair switchTo(TokenPair session, Organization organization) {
        return exchange(HttpMethod.POST, "/auth/switch-organization", session,
                new SwitchOrganizationRequest(organization.getId()), TokenPair.class).getBody();
    }
}
