package com.eventticket.organization.usecase;

import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.organization.domain.MemberView;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Owners;
import com.eventticket.organization.repository.MembershipRepository;

/** requirements/001 criteria 9 and 10. */
@Component
public class ChangeMemberRole {

    private static final Logger log = LoggerFactory.getLogger(ChangeMemberRole.class);

    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final Owners owners;
    private final AuditTrail audit;

    public ChangeMemberRole(MembershipRepository memberships, UserDirectory users, Owners owners, AuditTrail audit) {
        this.memberships = memberships;
        this.users = users;
        this.owners = owners;
        this.audit = audit;
    }

    @Transactional
    public MemberView change(UUID userId, Membership.Role role) {
        UUID organizationId = TenantContext.requireOrganizationId();
        owners.requireCallerIsOwner(organizationId);

        Membership membership = memberships.findOrThrow(organizationId, userId);

        // Demoting the last Owner is the same failure as removing them, so it is the same
        // check. Only run it when ownership is actually being given up.
        if (membership.isOwner() && role != Membership.Role.OWNER) {
            owners.requireAnotherOwnerBesides(organizationId, membership);
        }

        Membership.Role previous = membership.role();
        membership.changeRole(role);
        audit.record(organizationId, AuditTrail.MEMBER_ROLE_CHANGED, users.emailOf(userId) + " -> " + role);
        log.info("Changed member role userId={} from={} to={}", userId, previous, role);

        return new MemberView(membership, users.emailOf(userId), users.displayNameOf(userId));
    }
}
