package com.eventticket.organization.usecase;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.organization.domain.MemberView;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Owners;
import com.eventticket.organization.repository.MembershipRepository;
import com.eventticket.organization.repository.OrganizationRepository;

/** requirements/001 criteria 7 and 8. */
@Component
public class InviteMember {

    private static final Logger log = LoggerFactory.getLogger(InviteMember.class);

    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final UserDirectory users;
    private final Owners owners;
    private final AuditTrail audit;
    private final EmailSender email;
    private final String appBaseUrl;

    public InviteMember(MembershipRepository memberships, OrganizationRepository organizations,
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
    public MemberView invite(String emailAddress, Membership.Role role) {
        UUID organizationId = TenantContext.requireOrganizationId();
        owners.requireCallerIsOwner(organizationId);

        UUID invitedUserId = users.idForInvite(emailAddress);

        memberships.findByOrganizationIdAndUserId(organizationId, invitedUserId).ifPresent(m -> {
            throw new ApiException(ErrorCodes.ALREADY_EXISTS,
                    "That person is already a member of this organization.");
        });

        Membership membership = memberships.save(new Membership(organizationId, invitedUserId, role));
        audit.record(organizationId, AuditTrail.MEMBER_INVITED, emailAddress);
        log.info("Invited member userId={} role={}", invitedUserId, role);

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
