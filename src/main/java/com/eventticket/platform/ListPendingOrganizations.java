package com.eventticket.platform;

import com.eventticket.organization.Organization;
import com.eventticket.organization.OrganizationRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** requirements/001 criterion 6, the administrator's side of the approval gate. */
@Component
class ListPendingOrganizations {

    private final OrganizationRepository organizations;
    private final PlatformAdmins admins;

    ListPendingOrganizations(OrganizationRepository organizations, PlatformAdmins admins) {
        this.organizations = organizations;
        this.admins = admins;
    }

    @Transactional(readOnly = true)
    List<Organization> list(Organization.Status status, int limit) {
        admins.requireCallerIsPlatformAdmin();
        PageRequest page = PageRequest.of(0, limit);
        return status == null
                ? organizations.findAllByOrderByCreatedAtDesc(page)
                : organizations.findByStatusOrderByCreatedAtDesc(status, page);
    }
}
