package com.eventticket.platform.web;

import com.eventticket.api.PlatformAdminApi;
import com.eventticket.api.model.FeaturedSlot;
import com.eventticket.api.model.FeaturedSlotInput;
import com.eventticket.api.model.OrganizationDecisionRequest;
import com.eventticket.api.model.OrganizationPage;
import com.eventticket.api.model.OrganizationOwner;
import com.eventticket.api.model.OrganizationStatus;
import com.eventticket.organization.domain.Organization;
import com.eventticket.platform.domain.OrganizationReview;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import com.eventticket.platform.usecase.DecideOrganization;
import com.eventticket.platform.usecase.ListFeaturedSlots;
import com.eventticket.platform.usecase.ReplaceFeaturedSlots;
import com.eventticket.platform.usecase.ListPendingOrganizations;
import com.eventticket.platform.support.FeaturedEventSummaries;

@RestController
public class PlatformAdminController implements PlatformAdminApi {

    private final ListPendingOrganizations listPendingOrganizations;
    private final DecideOrganization decideOrganization;
    private final ListFeaturedSlots listFeaturedSlots;
    private final ReplaceFeaturedSlots replaceFeaturedSlots;
    private final FeaturedEventSummaries featuredEventSummaries;

    public PlatformAdminController(ListPendingOrganizations listPendingOrganizations,
                            DecideOrganization decideOrganization,
                            ListFeaturedSlots listFeaturedSlots,
                            ReplaceFeaturedSlots replaceFeaturedSlots,
                            FeaturedEventSummaries featuredEventSummaries) {
        this.listPendingOrganizations = listPendingOrganizations;
        this.decideOrganization = decideOrganization;
        this.listFeaturedSlots = listFeaturedSlots;
        this.replaceFeaturedSlots = replaceFeaturedSlots;
        this.featuredEventSummaries = featuredEventSummaries;
    }

    /**
     * The whole curated row, including what is not showing.
     *
     * <p>Every slot carries the Event it points at, because an administrator picking a
     * position is looking at titles rather than at uuids. That summary is the one the public
     * row uses - {@code EventMapper} is public for exactly this - so the two screens cannot
     * disagree about what an Event looks like.
     */
    @Override
    public ResponseEntity<List<FeaturedSlot>> adminFeaturedSlotsGet() {
        return ResponseEntity.ok(toDto(listFeaturedSlots.list()));
    }

    @Override
    public ResponseEntity<List<FeaturedSlot>> adminFeaturedSlotsPut(List<FeaturedSlotInput> request) {
        var placements = request.stream()
                .map(input -> new ReplaceFeaturedSlots.Placement(input.getEventId(),
                        input.getStartsAt().toInstant(), input.getEndsAt().toInstant()))
                .toList();
        return ResponseEntity.ok(toDto(replaceFeaturedSlots.replace(placements)));
    }

    /**
     * Slots with their Events.
     *
     * <p>The Events are read as a page rather than one per slot - a curated row of twenty
     * placements is one query, not twenty. A slot whose Event is no longer listable keeps its
     * place here and is absent from the public row; an administrator has to be able to see the
     * placement that stopped working in order to remove it.
     */
    private List<FeaturedSlot> toDto(List<com.eventticket.event.domain.FeaturedSlot> slots) {
        var byId = featuredEventSummaries.of(slots);
        return slots.stream().map(slot -> {
            var dto = new FeaturedSlot(slot.eventId(),
                    slot.startsAt().atOffset(ZoneOffset.UTC),
                    slot.endsAt().atOffset(ZoneOffset.UTC),
                    slot.id(), slot.position(), byId.get(slot.eventId()));
            return dto;
        }).toList();
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
