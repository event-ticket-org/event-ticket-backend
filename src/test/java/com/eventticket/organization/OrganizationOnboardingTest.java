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

        // Configured as an administrator, which is the only way to become one - there is
        // deliberately no request that grants it.
        TokenPair admin = signUp(PLATFORM_ADMIN_EMAIL);

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

    /**
     * requirements/001 criterion 14. Approving decides who may sell tickets to the public on a
     * page this platform endorses, and until this the administrator saw a name and a date.
     */
    @Test
    @DisplayName("14: the queue says who is accountable for each organization")
    void theQueueSaysWhoIsAccountable() {
        TokenPair owner = signUp("owner@example.com");
        createOrganization(owner, "Hanoi Live");
        TokenPair admin = signUp(PLATFORM_ADMIN_EMAIL);

        Organization waiting = only(queue(admin, "PENDING_APPROVAL"));

        assertThat(waiting.getOwners()).singleElement().satisfies(person -> {
            assertThat(person.getEmail()).isEqualTo("owner@example.com");
            assertThat(person.getDisplayName()).isNotBlank();
            // Verified because `signUp` follows the emailed link. Unverified is not a reason
            // to refuse on its own, and it is a thing worth being able to see.
            assertThat(person.getEmailVerified()).isTrue();
        });
    }

    /**
     * requirements/001 criterion 6. The reason was written, stored and emailed already; not
     * carrying it back meant the rejected queue was a list of names that did not say why any
     * of them was rejected, to the administrator who rejected them.
     */
    @Test
    @DisplayName("6: a rejection says why, and approving afterwards clears it")
    void aRejectionSaysWhyAndApprovingClearsIt() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");
        TokenPair admin = signUp(PLATFORM_ADMIN_EMAIL);

        var rejection = new OrganizationDecisionRequest(
                OrganizationDecisionRequest.DecisionEnum.REJECTED);
        rejection.setReason("The address given is a residential flat.");
        Organization rejected = exchange(HttpMethod.POST,
                "/admin/organizations/" + organization.getId() + "/decision", admin, rejection,
                Organization.class).getBody();

        assertThat(rejected.getDecisionReason()).isEqualTo("The address given is a residential flat.");
        assertThat(only(queue(admin, "REJECTED")).getDecisionReason())
                .isEqualTo("The address given is a residential flat.");

        // Criterion 15: a decision may be revisited, and criterion 6's reason must not outlive
        // the rejection it belonged to.
        Organization approved = exchange(HttpMethod.POST,
                "/admin/organizations/" + organization.getId() + "/decision", admin,
                new OrganizationDecisionRequest(OrganizationDecisionRequest.DecisionEnum.APPROVED),
                Organization.class).getBody();

        assertThat(approved.getStatus()).isEqualTo(OrganizationStatus.APPROVED);
        assertThat(approved.getDecisionReason()).isNull();
        assertThat(queue(admin, "REJECTED")).isEmpty();
    }

    /**
     * requirements/001 criterion 15. The domain always allowed this - both decisions set the
     * status unconditionally - and the only screen offered the buttons while a decision was
     * pending and never again, so an Organization rejected by mistake was permanently dead to
     * anybody using the product.
     */
    @Test
    @DisplayName("15: an approved organization can be stopped again")
    void anApprovedOrganizationCanBeStopped() {
        TokenPair owner = signUp("owner@example.com");
        Organization organization = createOrganization(owner, "Hanoi Live");
        TokenPair admin = signUp(PLATFORM_ADMIN_EMAIL);
        approve(organization);

        var rejection = new OrganizationDecisionRequest(
                OrganizationDecisionRequest.DecisionEnum.REJECTED);
        rejection.setReason("Reported by three buyers.");
        Organization stopped = exchange(HttpMethod.POST,
                "/admin/organizations/" + organization.getId() + "/decision", admin, rejection,
                Organization.class).getBody();

        assertThat(stopped.getStatus()).isEqualTo(OrganizationStatus.REJECTED);
        assertThat(stopped.getDecisionReason()).isEqualTo("Reported by three buyers.");
    }

    private java.util.List<Organization> queue(TokenPair admin, String status) {
        return exchange(HttpMethod.GET, "/admin/organizations?status={status}", admin, null,
                com.eventticket.api.model.OrganizationPage.class,
                java.util.Map.of("status", status)).getBody().getItems();
    }

    private static Organization only(java.util.List<Organization> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
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

}
