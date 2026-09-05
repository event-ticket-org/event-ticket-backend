package com.eventticket.organization;

import com.eventticket.shared.ApiException;
import com.eventticket.shared.AuditTrail;
import com.eventticket.shared.EmailSender;
import com.eventticket.shared.ErrorCodes;
import com.eventticket.shared.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** requirements/001 criteria 7 and 8. */
@Component
class InviteMember {

    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final UserDirectory users;
    private final Owners owners;
    private final AuditTrail audit;
    private final EmailSender email;
    private final String appBaseUrl;

    InviteMember(MembershipRepository memberships, OrganizationRepository organizations,
                 UserDirectory users, Owners owners, AuditTrail audit, EmailSender email,
                 @Value("${app.base-url}") String appBaseUrl) {
        this.memberships = memberships;
        this.organizations = organizations;
        this.users = users;
        this.owners = owners;
        this.audit = audit;
        this.email = email;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    MemberView invite(String emailAddress, Membership.Role role) {
        UUID organizationId = TenantContext.requireOrganizationId();
        owners.requireCallerIsOwner(organizationId);

        UUID invitedUserId = users.idForInvite(emailAddress);

        memberships.findByOrganizationIdAndUserId(organizationId, invitedUserId).ifPresent(m -> {
            throw new ApiException(ErrorCodes.ALREADY_EXISTS,
                    "That person is already a member of this organization.");
        });

        Membership membership = memberships.save(new Membership(organizationId, invitedUserId, role));
        audit.record(organizationId, AuditTrail.MEMBER_INVITED, emailAddress);

        notifyInvitee(emailAddress, organizations.findOrThrow(organizationId).name());

        return new MemberView(membership, emailAddress, users.displayNameOf(invitedUserId));
    }

    private void notifyInvitee(String emailAddress, String organizationName) {
        email.send(emailAddress, "You have been added to " + organizationName,
                """
                %s has added you to their team on Event Ticketing.

                Sign in, or create an account with this email address if you do not have one:
                %s

                Your access is already set up and will be ready once your email is confirmed.
                """.formatted(organizationName, appBaseUrl));
    }
}
