package com.eventticket.platform;

import com.eventticket.organization.Membership;
import com.eventticket.organization.MembershipRepository;
import com.eventticket.organization.Organization;
import com.eventticket.organization.OrganizationRepository;
import com.eventticket.shared.AuditTrail;
import com.eventticket.shared.EmailSender;
import com.eventticket.shared.TenantContext;
import com.eventticket.shared.TenantPublisher;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** requirements/001 criterion 6. Both outcomes email the Owner. */
@Component
class DecideOrganization {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final PlatformAdmins admins;
    private final AuditTrail audit;
    private final EmailSender email;
    private final TenantPublisher tenant;

    DecideOrganization(OrganizationRepository organizations, MembershipRepository memberships,
                       UserDirectory users, PlatformAdmins admins, AuditTrail audit,
                       EmailSender email, TenantPublisher tenant) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
        this.admins = admins;
        this.audit = audit;
        this.email = email;
        this.tenant = tenant;
    }

    @Transactional
    Organization decide(UUID organizationId, boolean approved, String reason) {
        admins.requireCallerIsPlatformAdmin();
        UUID adminUserId = TenantContext.requireUserId();

        Organization organization = organizations.findOrThrow(organizationId);

        // An administrator has no active Organization of their own, so the tenant is adopted
        // for the one being decided on. Without it the audit insert is refused and the owner
        // lookup returns nothing - the policies do not make an exception for administrators,
        // which is the correct default: the exception is granted here, explicitly and once.
        tenant.adopt(adminUserId, organizationId);

        if (approved) {
            Organization decided = organizations.save(applyApproval(organization));
            audit.record(organizationId, AuditTrail.ORGANIZATION_APPROVED, organization.name());
            notifyOwners(organizationId, organization.name(), true, null);
            return decided;
        }

        Organization decided = organizations.save(applyRejection(organization, reason));
        audit.record(organizationId, AuditTrail.ORGANIZATION_REJECTED, organization.name());
        notifyOwners(organizationId, organization.name(), false, reason);
        return decided;
    }

    private static Organization applyApproval(Organization organization) {
        organization.approveByPlatform();
        return organization;
    }

    private static Organization applyRejection(Organization organization, String reason) {
        organization.rejectByPlatform(reason);
        return organization;
    }

    private void notifyOwners(UUID organizationId, String name, boolean approved, String reason) {
        String subject = approved
                ? name + " has been approved"
                : "About your organization, " + name;
        String body = approved
                ? """
                  %s has been approved. You can now publish events and sell tickets.
                  """.formatted(name)
                : """
                  We were not able to approve %s at this time.

                  %s
                  """.formatted(name, reason == null ? "No reason was given." : reason);

        memberships.findByOrganizationId(organizationId).stream()
                .filter(Membership::isOwner)
                .forEach(owner -> email.send(users.emailOf(owner.userId()), subject, body));
    }
}
