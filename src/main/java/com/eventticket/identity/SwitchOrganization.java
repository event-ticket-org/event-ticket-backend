package com.eventticket.identity;

import com.eventticket.organization.MembershipRepository;
import com.eventticket.shared.ApiException;
import com.eventticket.shared.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criterion 11. Changing the active Organization reissues the token, because
 * the active Organization is a claim inside it.
 *
 * <p>This endpoint exists so that the tenant is never a URL parameter. Switching is an
 * explicit, authorised act that produces a new token; passing an organization id alongside a
 * request is not, and would be the exact bug ADR-0004 exists to prevent.
 */
@Component
class SwitchOrganization {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final SessionIssuer sessions;

    SwitchOrganization(AppUserRepository users, MembershipRepository memberships, SessionIssuer sessions) {
        this.users = users;
        this.memberships = memberships;
        this.sessions = sessions;
    }

    @Transactional
    Session switchTo(UUID organizationId) {
        UUID userId = TenantContext.requireUserId();

        memberships.findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> ApiException.notPermitted(
                        "You are not a member of that organization."));

        return sessions.issueFor(users.findOrThrow(userId), organizationId);
    }
}
