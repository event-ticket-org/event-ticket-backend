package com.eventticket.organization;

import com.eventticket.shared.AuditTrail;
import com.eventticket.shared.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criteria 9 and 10.
 *
 * <p>Removal takes effect immediately where it matters. The scan endpoint checks live
 * Membership rather than trusting token claims (ADR-0005), so a Gate Staff volunteer removed
 * after an event stops being able to scan at once, without waiting for a token to expire.
 */
@Component
class RemoveMember {

    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final Owners owners;
    private final AuditTrail audit;

    RemoveMember(MembershipRepository memberships, UserDirectory users, Owners owners, AuditTrail audit) {
        this.memberships = memberships;
        this.users = users;
        this.owners = owners;
        this.audit = audit;
    }

    @Transactional
    void remove(UUID userId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        owners.requireCallerIsOwner(organizationId);

        Membership membership = memberships.findOrThrow(organizationId, userId);
        owners.requireAnotherOwnerBesides(organizationId, membership);

        String removed = users.emailOf(userId);
        memberships.delete(membership);
        audit.record(organizationId, AuditTrail.MEMBER_REMOVED, removed);
    }
}
