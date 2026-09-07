package com.eventticket.platform.usecase;

import com.eventticket.organization.domain.Organization;
import com.eventticket.organization.repository.OrganizationRepository;
import com.eventticket.platform.domain.OrganizationReview;
import com.eventticket.platform.support.OrganizationOwnerIds;
import com.eventticket.platform.support.PlatformAdmins;
import com.eventticket.shared.DirectoryUser;
import com.eventticket.shared.UserDirectory;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** requirements/001 criteria 6 and 14, the administrator's side of the approval gate. */
@Component
public class ListPendingOrganizations {

    private final OrganizationRepository organizations;
    private final OrganizationOwnerIds ownerLookup;
    private final UserDirectory users;
    private final PlatformAdmins admins;

    public ListPendingOrganizations(OrganizationRepository organizations,
                                    OrganizationOwnerIds ownerLookup, UserDirectory users,
                                    PlatformAdmins admins) {
        this.organizations = organizations;
        this.ownerLookup = ownerLookup;
        this.users = users;
        this.admins = admins;
    }

    @Transactional(readOnly = true)
    public List<OrganizationReview> list(Organization.Status status, int limit) {
        admins.requireCallerIsPlatformAdmin();
        PageRequest page = PageRequest.of(0, limit);
        List<Organization> found = status == null
                ? organizations.findAllByOrderByCreatedAtDesc(page)
                : organizations.findByStatusOrderByCreatedAtDesc(status, page);
        if (found.isEmpty()) {
            return List.of();
        }

        // Two queries for the whole page rather than two per row. A lookup per Organization
        // reads the same on a screen with four of them and is a hundred round trips on a
        // screen with fifty, which is the shape that only shows up once somebody has data.
        //
        // Through the definer function rather than the repository: an administrator is a
        // member of nothing, so the membership policy correctly hides every row here. That is
        // not a mistake to route around in a policy - see V12.
        Map<UUID, List<UUID>> ownerIds = ownerLookup
                .forOrganizations(found.stream().map(Organization::id).toList());
        Map<UUID, DirectoryUser> people = users.usersOf(
                ownerIds.values().stream().flatMap(List::stream).distinct().toList());

        return found.stream()
                .map(organization -> new OrganizationReview(organization,
                        ownersOf(organization, ownerIds, people)))
                .toList();
    }

    /**
     * By name, so the same Organization lists its owners the same way twice. Membership rows
     * come back in whatever order the database liked, and a list that reorders itself between
     * two loads of the same screen reads as something having changed.
     */
    private static List<DirectoryUser> ownersOf(Organization organization,
                                                Map<UUID, List<UUID>> ownerIds,
                                                Map<UUID, DirectoryUser> people) {
        return ownerIds.getOrDefault(organization.id(), List.of()).stream()
                .map(people::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(DirectoryUser::displayName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
