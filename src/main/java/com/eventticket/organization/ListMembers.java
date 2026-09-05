package com.eventticket.organization;

import com.eventticket.shared.TenantContext;
import com.eventticket.shared.UserDirectory;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criterion 7. No organization filter is passed: row-level security has
 * already narrowed the table to the active Organization, which is the point of ADR-0004.
 */
@Component
class ListMembers {

    private final MembershipRepository memberships;
    private final UserDirectory users;

    ListMembers(MembershipRepository memberships, UserDirectory users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Transactional(readOnly = true)
    List<MemberView> list() {
        return memberships.findByOrganizationId(TenantContext.requireOrganizationId()).stream()
                .map(m -> new MemberView(m, users.emailOf(m.userId()), users.displayNameOf(m.userId())))
                .toList();
    }
}
