package com.eventticket.organization.usecase;

import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Owners;
import com.eventticket.organization.repository.MembershipRepository;

/**
 * requirements/001 criteria 9 and 10.
 *
 * <p>Removal takes effect immediately where it matters. The scan endpoint checks live
 * Membership rather than trusting token claims (ADR-0005), so a Gate Staff volunteer removed
 * after an event stops being able to scan at once, without waiting for a token to expire.
 */
@Component
public class RemoveMember {

    private static final Logger log = LoggerFactory.getLogger(RemoveMember.class);

    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final Owners owners;
    private final AuditTrail audit;

    public RemoveMember(MembershipRepository memberships, UserDirectory users, Owners owners, AuditTrail audit) {
        this.memberships = memberships;
        this.users = users;
        this.owners = owners;
        this.audit = audit;
    }

    @Transactional
    public void remove(UUID userId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        owners.requireCallerIsOwner(organizationId);

        Membership membership = memberships.findOrThrow(organizationId, userId);
        owners.requireAnotherOwnerBesides(organizationId, membership);

        String removed = users.emailOf(userId);
        memberships.delete(membership);
        audit.record(organizationId, AuditTrail.MEMBER_REMOVED, removed);
        // Scanning access ends immediately; other endpoints follow within the token lifetime.
        log.info("Removed member userId={} role={}", userId, membership.role());
    }
}
