package com.eventticket.organization.usecase;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.tenancy.TenantPublisher;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.MembershipRepository;
import com.eventticket.organization.repository.OrganizationRepository;

/**
 * requirements/001 criteria 2 and 3. The creator becomes the Owner, and the Organization
 * starts unapproved.
 */
@Component
public class CreateOrganization {

    private static final Logger log = LoggerFactory.getLogger(CreateOrganization.class);

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AuditTrail audit;
    private final TenantPublisher tenant;
    private final UserDirectory users;

    public CreateOrganization(OrganizationRepository organizations, MembershipRepository memberships,
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
            log.warn("Organization creation refused: email not verified userId={}", userId);
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
        log.info("Created organization organizationId={} awaiting approval", organization.id());

        return organization;
    }
}
