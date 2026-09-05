package com.eventticket.platform;

import com.eventticket.api.PlatformAdminApi;
import com.eventticket.api.model.OrganizationDecisionRequest;
import com.eventticket.api.model.OrganizationPage;
import com.eventticket.api.model.OrganizationStatus;
import com.eventticket.organization.Organization;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlatformAdminController implements PlatformAdminApi {

    private final ListPendingOrganizations listPendingOrganizations;
    private final DecideOrganization decideOrganization;

    PlatformAdminController(ListPendingOrganizations listPendingOrganizations,
                            DecideOrganization decideOrganization) {
        this.listPendingOrganizations = listPendingOrganizations;
        this.decideOrganization = decideOrganization;
    }

    @Override
    public ResponseEntity<OrganizationPage> adminOrganizationsGet(
            OrganizationStatus status, Integer limit, String cursor) {

        var organizations = listPendingOrganizations.list(
                status == null ? null : Organization.Status.valueOf(status.getValue()), limit);

        OrganizationPage page = new OrganizationPage();
        organizations.forEach(o -> page.addItemsItem(toDto(o)));
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Organization> adminOrganizationsOrganizationIdDecisionPost(
            UUID organizationId, OrganizationDecisionRequest request) {

        boolean approved = request.getDecision() == OrganizationDecisionRequest.DecisionEnum.APPROVED;
        return ResponseEntity.ok(toDto(decideOrganization.decide(organizationId, approved, request.getReason())));
    }

    private static com.eventticket.api.model.Organization toDto(Organization organization) {
        var dto = new com.eventticket.api.model.Organization(
                organization.id(), organization.name(),
                OrganizationStatus.fromValue(organization.status().name()));
        dto.setCreatedAt(organization.createdAt().atOffset(ZoneOffset.UTC));
        return dto;
    }
}
