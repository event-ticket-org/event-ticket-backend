package com.eventticket.identity.usecase;

import com.eventticket.organization.repository.MembershipRepository;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.domain.Session;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.security.SessionIssuer;
import com.eventticket.organization.domain.Organization;

/**
 * requirements/001 criterion 11. Changing the active Organization reissues the token, because
 * the active Organization is a claim inside it.
 *
 * <p>This endpoint exists so that the tenant is never a URL parameter. Switching is an
 * explicit, authorised act that produces a new token; passing an organization id alongside a
 * request is not, and would be the exact bug ADR-0004 exists to prevent.
 */
@Component
public class SwitchOrganization {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final SessionIssuer sessions;

    public SwitchOrganization(AppUserRepository users, MembershipRepository memberships, SessionIssuer sessions) {
        this.users = users;
        this.memberships = memberships;
        this.sessions = sessions;
    }

    @Transactional
    public Session switchTo(UUID organizationId) {
        UUID userId = TenantContext.requireUserId();

        memberships.findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> ApiException.notPermitted(
                        "You are not a member of that organization."));

        return sessions.issueFor(users.findOrThrow(userId), organizationId);
    }
}
