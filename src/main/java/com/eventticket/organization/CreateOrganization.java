package com.eventticket.organization;

import com.eventticket.shared.ApiException;
import com.eventticket.shared.AuditTrail;
import com.eventticket.shared.ErrorCodes;
import com.eventticket.shared.TenantContext;
import com.eventticket.shared.TenantPublisher;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criteria 2 and 3. The creator becomes the Owner, and the Organization
 * starts unapproved.
 */
@Component
public class CreateOrganization {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AuditTrail audit;
    private final TenantPublisher tenant;
    private final UserDirectory users;

    CreateOrganization(OrganizationRepository organizations, MembershipRepository memberships,
                       AuditTrail audit, TenantPublisher tenant, UserDirectory users) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.audit = audit;
        this.tenant = tenant;
        this.users = users;
    }

    @Transactional
    public Organization create(String name) {
        UUID userId = TenantContext.requireUserId();
        if (!users.isVerified(userId)) {
            throw new ApiException(ErrorCodes.EMAIL_NOT_VERIFIED,
                    "Confirm your email address before creating an organization.");
        }

        Organization organization = organizations.save(new Organization(name));

        // The caller has no active Organization yet - they are creating one - so the tenant
        // must be adopted before writing rows the policies guard. Without this the insert is
        // refused by the membership policy's WITH CHECK, which is the correct behaviour and
        // exactly why the adoption is explicit rather than assumed.
        tenant.adopt(userId, organization.id());

        memberships.save(new Membership(organization.id(), userId, Membership.Role.OWNER));
        audit.record(organization.id(), AuditTrail.ORGANIZATION_CREATED, name);

        return organization;
    }
}
