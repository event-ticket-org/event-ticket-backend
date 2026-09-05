package com.eventticket.identity.usecase;

import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.MeView;
import com.eventticket.identity.repository.AppUserRepository;

/**
 * requirements/001 criterion 11: a User sees every Organization they belong to, whichever one
 * is currently active. The membership policy's own-memberships branch is what makes this
 * readable across tenants without weakening isolation for anyone else's rows.
 */
@Component
public class GetMe {

    private final AppUserRepository users;
    private final com.eventticket.organization.repository.MembershipRepository memberships;
    private final OrganizationRepository organizations;

    public GetMe(AppUserRepository users, com.eventticket.organization.repository.MembershipRepository memberships,
          OrganizationRepository organizations) {
        this.users = users;
        this.memberships = memberships;
        this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public MeView get() {
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
