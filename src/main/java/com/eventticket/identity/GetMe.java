package com.eventticket.identity;

import com.eventticket.organization.Membership;
import com.eventticket.organization.Organization;
import com.eventticket.organization.OrganizationRepository;
import com.eventticket.shared.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criterion 11: a User sees every Organization they belong to, whichever one
 * is currently active. The membership policy's own-memberships branch is what makes this
 * readable across tenants without weakening isolation for anyone else's rows.
 */
@Component
class GetMe {

    private final AppUserRepository users;
    private final com.eventticket.organization.MembershipRepository memberships;
    private final OrganizationRepository organizations;

    GetMe(AppUserRepository users, com.eventticket.organization.MembershipRepository memberships,
          OrganizationRepository organizations) {
        this.users = users;
        this.memberships = memberships;
        this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    MeView get() {
        UUID userId = TenantContext.requireUserId();

        AppUser user = users.findOrThrow(userId);
        List<Membership> held = memberships.findByUserId(userId);

        Map<UUID, String> names = organizations
                .findAllById(held.stream().map(Membership::organizationId).toList())
                .stream()
                .collect(Collectors.toMap(Organization::id, Organization::name, (a, b) -> a));

        return new MeView(user, held, names);
    }
}
