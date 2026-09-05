package com.eventticket.organization;

import com.eventticket.api.OrganizationApi;
import com.eventticket.api.model.ChangeMemberRoleRequest;
import com.eventticket.api.model.CreateOrganizationRequest;
import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.OrganizationStatus;
import com.eventticket.api.model.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrganizationController implements OrganizationApi {

    private final CreateOrganization createOrganization;
    private final ListMembers listMembers;
    private final InviteMember inviteMember;
    private final ChangeMemberRole changeMemberRole;
    private final RemoveMember removeMember;

    OrganizationController(CreateOrganization createOrganization, ListMembers listMembers,
                           InviteMember inviteMember, ChangeMemberRole changeMemberRole,
                           RemoveMember removeMember) {
        this.createOrganization = createOrganization;
        this.listMembers = listMembers;
        this.inviteMember = inviteMember;
        this.changeMemberRole = changeMemberRole;
        this.removeMember = removeMember;
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Organization> organizationsPost(
            CreateOrganizationRequest request) {
        Organization created = createOrganization.create(request.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    @Override
    public ResponseEntity<List<com.eventticket.api.model.Membership>> organizationMembersGet() {
        return ResponseEntity.ok(listMembers.list().stream().map(OrganizationController::toDto).toList());
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Membership> organizationMembersPost(
            InviteMemberRequest request) {
        MemberView invited = inviteMember.invite(request.getEmail(), role(request.getRole()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(invited));
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.Membership> organizationMembersUserIdPatch(
            UUID userId, ChangeMemberRoleRequest request) {
        return ResponseEntity.ok(toDto(changeMemberRole.change(userId, role(request.getRole()))));
    }

    @Override
    public ResponseEntity<Void> organizationMembersUserIdDelete(UUID userId) {
        removeMember.remove(userId);
        return ResponseEntity.noContent().build();
    }

    private static Membership.Role role(Role dto) {
        return Membership.Role.valueOf(dto.getValue());
    }

    static com.eventticket.api.model.Organization toDto(Organization organization) {
        var dto = new com.eventticket.api.model.Organization(
                organization.id(), organization.name(),
                OrganizationStatus.fromValue(organization.status().name()));
        dto.setCreatedAt(organization.createdAt().atOffset(java.time.ZoneOffset.UTC));
        return dto;
    }

    private static com.eventticket.api.model.Membership toDto(MemberView view) {
        var dto = new com.eventticket.api.model.Membership(
                view.membership().userId(), view.membership().organizationId(),
                Role.fromValue(view.membership().role().name()));
        dto.setEmail(view.email());
        dto.setDisplayName(view.displayName());
        return dto;
    }
}
