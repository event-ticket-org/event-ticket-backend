package com.eventticket.organization.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.eventticket.organization.repository.MembershipRepository;

/**
 * Answers "may this caller administer the Organization?" and "would this leave it without an
 * Owner?".
 *
 * <p>Both questions belong to the Organization rather than to any one use case, which is why
 * they live here and not in a helper shared between the three use cases that ask them
 * (ADR-0001: shared logic goes down, never sideways).
 */
@Component
public class Owners {

    private static final Logger log = LoggerFactory.getLogger(Owners.class);

    private final MembershipRepository memberships;

    public Owners(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    /** Only an Owner may change who belongs to an Organization. */
    public Membership requireCallerIsOwner(UUID organizationId) {
        Membership caller = memberships.findOrThrow(organizationId, TenantContext.requireUserId());
        if (!caller.isOwner()) {
            log.warn("Member management refused: caller is {} organizationId={}",
                    caller.role(), organizationId);
            throw ApiException.notPermitted("Only an owner can manage members.");
        }
        return caller;
    }

    /**
     * requirements/001 criterion 10. Checked before the change rather than after, because
     * "the last owner" is only meaningful while they are still there.
     */
    public void requireAnotherOwnerBesides(UUID organizationId, Membership losingOwnership) {
        if (!losingOwnership.isOwner()) {
            return;
        }
        if (memberships.countByOrganizationIdAndRole(organizationId, Membership.Role.OWNER) <= 1) {
            log.warn("Refused: would leave organizationId={} without an owner", organizationId);
            throw new ApiException(ErrorCodes.LAST_OWNER,
                    "An organization must keep at least one owner. Make someone else an owner first.");
        }
    }
}
