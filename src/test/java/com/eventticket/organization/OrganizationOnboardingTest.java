package com.eventticket.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.ChangeMemberRoleRequest;
import com.eventticket.api.model.CreateOrganizationRequest;
import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.Membership;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.OrganizationDecisionRequest;
import com.eventticket.api.model.OrganizationStatus;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.SwitchOrganizationRequest;
import com.eventticket.api.model.TokenPair;
import com.eventticket.support.ApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Knowledge base requirements/001. Criterion numbers refer to that document. */
class OrganizationOnboardingTest extends ApiTest {

    @Test
    @DisplayName("1: registering sends a verification email and signing in follows it")
    void registerAndVerify() {
        TokenPair session = signUp("owner@example.com");

        assertThat(session.getAccessToken()).isNotBlank();
        assertThat(session.getActiveOrganizationId()).isNull();

        Me me = exchange(HttpMethod.GET, "/me", session, null, Me.class).getBody();
        assertThat(me.getEmail()).isEqualTo("owner@example.com");
        assertThat(me.getEmailVerified()).isTrue();
        assertThat(me.getMemberships()).isEmpty();
    }

    @Test
    @DisplayName("2, 3: creating an organization makes the creator its owner, pending approval")
    void createOrganization() {
        TokenPair session = signUp("owner@example.com");

        Organization created = createOrganization(session, "Hanoi Live");

        assertThat(created.getName()).isEqualTo("Hanoi Live");
        assertThat(created.getStatus()).isEqualTo(OrganizationStatus.PENDING_APPROVAL);

        Me me = exchange(HttpMethod.GET, "/me", session, null, Me.class).getBody();
        assertThat(me.getMemberships()).singleElement()
                .satisfies(m -> {
                    assertThat(m.getOrganizationId()).isEqualTo(created.getId());
                    assertThat(m.getRole()).isEqualTo(Role.OWNER);
                    assertThat(m.getOrganizationName()).isEqualTo("Hanoi Live");
                });
    }

    @Test
    @DisplayName("1: an unverified user cannot create an organization")
    void unverifiedCannotCreateOrganization() {
        http.postForEntity("/auth/register",
                new com.eventticket.api.model.RegisterRequest(
                        "unverified@example.com", "correct-horse-battery", "Unverified"),
                Void.class);

        TokenPair session = signIn("unverified@example.com");

        ResponseEntity<Error> response = exchange(HttpMethod.POST, "/organizations", session,
                new CreateOrganizationRequest("Too Soon"), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    @DisplayName("7, 8: an owner can invite someone who has no account yet")
    void inviteSomeoneWithoutAnAccount() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");
        TokenPair active = switchTo(owner, organization);

        ResponseEntity<Membership> invited = exchange(HttpMethod.POST, "/organization/members", active,
                new InviteMemberRequest("volunteer@example.com", Role.GATE_STAFF), Membership.class);

        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invited.getBody().getRole()).isEqualTo(Role.GATE_STAFF);

        // The invitee registers afterwards and the membership is already waiting for them.
        TokenPair volunteer = signUp("volunteer@example.com");
        Me me = exchange(HttpMethod.GET, "/me", volunteer, null, Me.class).getBody();
        assertThat(me.getMemberships()).singleElement()
                .satisfies(m -> assertThat(m.getRole()).isEqualTo(Role.GATE_STAFF));
    }

    @Test
    @DisplayName("10: the last owner cannot be removed")
    void lastOwnerCannotBeRemoved() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");
        TokenPair active = switchTo(owner, organization);

        Me me = exchange(HttpMethod.GET, "/me", active, null, Me.class).getBody();

        ResponseEntity<Error> response = exchange(HttpMethod.DELETE,
                "/organization/members/" + me.getId(), active, null, Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.LAST_OWNER);
    }

    @Test
    @DisplayName("10: the last owner cannot be demoted either")
    void lastOwnerCannotBeDemoted() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");
        TokenPair active = switchTo(owner, organization);
        Me me = exchange(HttpMethod.GET, "/me", active, null, Me.class).getBody();

        ResponseEntity<Error> response = exchange(HttpMethod.PATCH,
                "/organization/members/" + me.getId(), active,
                new ChangeMemberRoleRequest(Role.MANAGER), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.LAST_OWNER);
    }

    @Test
    @DisplayName("11: switching to an organization you do not belong to is refused")
    void cannotSwitchWithoutMembership() {
        TokenPair stranger = signUp("stranger@example.com");
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");

        ResponseEntity<Error> response = exchange(HttpMethod.POST, "/auth/switch-organization",
                stranger, new SwitchOrganizationRequest(organization.getId()), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_PERMITTED);
    }

    @Test
    @DisplayName("6: a platform administrator approves an organization and the owner is emailed")
    void platformAdminApproves() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");

        signUp("admin@example.com");
        makePlatformAdmin("admin@example.com");
        TokenPair admin = signIn("admin@example.com");

        ResponseEntity<Organization> decided = exchange(HttpMethod.POST,
                "/admin/organizations/" + organization.getId() + "/decision", admin,
                new OrganizationDecisionRequest(OrganizationDecisionRequest.DecisionEnum.APPROVED),
                Organization.class);

        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decided.getBody().getStatus()).isEqualTo(OrganizationStatus.APPROVED);

        assertThat(email.sent())
                .anySatisfy(m -> {
                    assertThat(m.to()).isEqualTo("owner@example.com");
                    assertThat(m.subject()).contains("approved");
                });
    }

    @Test
    @DisplayName("6: an ordinary user cannot approve organizations")
    void nonAdminCannotApprove() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");

        ResponseEntity<Error> response = exchange(HttpMethod.POST,
                "/admin/organizations/" + organization.getId() + "/decision", owner,
                new OrganizationDecisionRequest(OrganizationDecisionRequest.DecisionEnum.APPROVED),
                Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private TokenPair switchTo(TokenPair session, Organization organization) {
        return exchange(HttpMethod.POST, "/auth/switch-organization", session,
                new SwitchOrganizationRequest(organization.getId()), TokenPair.class).getBody();
    }
}
