package com.eventticket.organization;

import com.eventticket.shared.ApiException;
import com.eventticket.shared.ErrorCodes;
import com.eventticket.shared.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers "may this caller administer the Organization?" and "would this leave it without an
 * Owner?".
 *
 * <p>Both questions belong to the Organization rather than to any one use case, which is why
 * they live here and not in a helper shared between the three use cases that ask them
 * (ADR-0001: shared logic goes down, never sideways).
 */
@Component
class Owners {

    private final MembershipRepository memberships;

    Owners(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    /** Only an Owner may change who belongs to an Organization. */
    Membership requireCallerIsOwner(UUID organizationId) {
        Membership caller = memberships.findOrThrow(organizationId, TenantContext.requireUserId());
        if (!caller.isOwner()) {
            throw ApiException.notPermitted("Only an owner can manage members.");
        }
        return caller;
    }

    /**
     * requirements/001 criterion 10. Checked before the change rather than after, because
     * "the last owner" is only meaningful while they are still there.
     */
    void requireAnotherOwnerBesides(UUID organizationId, Membership losingOwnership) {
        if (!losingOwnership.isOwner()) {
            return;
        }
        if (memberships.countByOrganizationIdAndRole(organizationId, Membership.Role.OWNER) <= 1) {
            throw new ApiException(ErrorCodes.LAST_OWNER,
                    "An organization must keep at least one owner. Make someone else an owner first.");
        }
    }
}
