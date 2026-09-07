package com.eventticket.platform.usecase;

import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.repository.MembershipRepository;
import com.eventticket.organization.domain.Organization;
import com.eventticket.platform.domain.OrganizationReview;
import com.eventticket.shared.DirectoryUser;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.tenancy.TenantPublisher;
import com.eventticket.shared.UserDirectory;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.platform.support.PlatformAdmins;

/** requirements/001 criterion 6. Both outcomes email the Owner. */
@Component
public class DecideOrganization {

    private static final Logger log = LoggerFactory.getLogger(DecideOrganization.class);

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final PlatformAdmins admins;
    private final AuditTrail audit;
    private final EmailSender email;
    private final TenantPublisher tenant;

    public DecideOrganization(OrganizationRepository organizations, MembershipRepository memberships,
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
    public OrganizationReview decide(UUID organizationId, boolean approved, String reason) {
        admins.requireCallerIsPlatformAdmin();
        UUID adminUserId = TenantContext.requireUserId();

        Organization organization = organizations.findOrThrow(organizationId);

        // An administrator has no active Organization of their own, so the tenant is adopted
        // for the one being decided on. Without it the audit insert is refused and the owner
        // lookup returns nothing - the policies do not make an exception for administrators,
        // which is the correct default: the exception is granted here, explicitly and once.
        tenant.adopt(adminUserId, organizationId);

        // Read once and used twice: the same people are emailed and reported back, and asking
        // twice invites the two answers to differ.
        List<Membership> owners = memberships.findByOrganizationId(organizationId).stream()
                .filter(Membership::isOwner)
                .toList();

        if (approved) {
            Organization decided = organizations.save(applyApproval(organization));
            audit.record(organizationId, AuditTrail.ORGANIZATION_APPROVED, organization.name());
            log.info("Approved organization organizationId={} by admin userId={}", organizationId, adminUserId);
            notifyOwners(owners, organization.name(), true, null);
            return new OrganizationReview(decided, describe(owners));
        }

        Organization decided = organizations.save(applyRejection(organization, reason));
        audit.record(organizationId, AuditTrail.ORGANIZATION_REJECTED, organization.name());
        log.info("Rejected organization organizationId={} by admin userId={}", organizationId, adminUserId);
        notifyOwners(owners, organization.name(), false, reason);
        return new OrganizationReview(decided, describe(owners));
    }

    private static Organization applyApproval(Organization organization) {
        organization.approveByPlatform();
        return organization;
    }

    private static Organization applyRejection(Organization organization, String reason) {
        organization.rejectByPlatform(reason);
        return organization;
    }

    /** The owners as the contract describes them, so the response says who was decided about. */
    private List<DirectoryUser> describe(List<Membership> owners) {
        Map<UUID, DirectoryUser> people = users.usersOf(
                owners.stream().map(Membership::userId).toList());
        return owners.stream()
                .map(owner -> people.get(owner.userId()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DirectoryUser::displayName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private void notifyOwners(List<Membership> owners, String name, boolean approved, String reason) {
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

        owners.forEach(owner -> email.send(users.emailOf(owner.userId()), subject, body));
    }
}
