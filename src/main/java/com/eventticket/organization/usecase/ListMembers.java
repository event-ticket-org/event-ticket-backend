package com.eventticket.organization.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.organization.domain.MemberView;
import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.MembershipRepository;

/**
 * requirements/001 criterion 7. No organization filter is passed: row-level security has
 * already narrowed the table to the active Organization, which is the point of ADR-0004.
 */
@Component
public class ListMembers {

    private final MembershipRepository memberships;
    private final UserDirectory users;

    public ListMembers(MembershipRepository memberships, UserDirectory users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<MemberView> list() {
        return memberships.findByOrganizationId(TenantContext.requireOrganizationId()).stream()
                .map(m -> new MemberView(m, users.emailOf(m.userId()), users.displayNameOf(m.userId())))
                .toList();
    }
}
