package com.eventticket.platform.web;

import com.eventticket.api.PlatformAdminApi;
import com.eventticket.api.model.OrganizationDecisionRequest;
import com.eventticket.api.model.OrganizationPage;
import com.eventticket.api.model.OrganizationOwner;
import com.eventticket.api.model.OrganizationStatus;
import com.eventticket.organization.domain.Organization;
import com.eventticket.platform.domain.OrganizationReview;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import com.eventticket.platform.usecase.DecideOrganization;
import com.eventticket.platform.usecase.ListPendingOrganizations;

@RestController
public class PlatformAdminController implements PlatformAdminApi {

    private final ListPendingOrganizations listPendingOrganizations;
    private final DecideOrganization decideOrganization;

    public PlatformAdminController(ListPendingOrganizations listPendingOrganizations,
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

    /**
     * requirements/001 criteria 6 and 14: who is accountable, and why a rejected Organization
     * was rejected.
     *
     * <p>The reason was already written, already stored and already emailed, and simply not
     * carried here - so the rejected queue was a list of names that did not say why any of
     * them was rejected, to the administrator who rejected them.
     */
    private static com.eventticket.api.model.Organization toDto(OrganizationReview review) {
        Organization organization = review.organization();
        var dto = new com.eventticket.api.model.Organization(
                organization.id(), organization.name(),
                OrganizationStatus.fromValue(organization.status().name()));
        dto.setCreatedAt(organization.createdAt().atOffset(ZoneOffset.UTC));
        dto.setDecisionReason(organization.decisionReason());
        review.owners().forEach(owner -> dto.addOwnersItem(new OrganizationOwner(
                owner.displayName(), owner.email(), owner.emailVerified())));
        return dto;
    }
}
