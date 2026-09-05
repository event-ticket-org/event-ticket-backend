package com.eventticket.platform.usecase;

import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.platform.support.PlatformAdmins;

/** requirements/001 criterion 6, the administrator's side of the approval gate. */
@Component
public class ListPendingOrganizations {

    private final OrganizationRepository organizations;
    private final PlatformAdmins admins;

    public ListPendingOrganizations(OrganizationRepository organizations, PlatformAdmins admins) {
        this.organizations = organizations;
        this.admins = admins;
    }

    @Transactional(readOnly = true)
    public List<Organization> list(Organization.Status status, int limit) {
        admins.requireCallerIsPlatformAdmin();
        PageRequest page = PageRequest.of(0, limit);
        return status == null
                ? organizations.findAllByOrderByCreatedAtDesc(page)
                : organizations.findByStatusOrderByCreatedAtDesc(status, page);
    }
}
