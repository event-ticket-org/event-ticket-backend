package com.eventticket.organization.repository;

import com.eventticket.shared.error.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;

/**
 * Reads here are already narrowed by row-level security to the active Organization, plus the
 * signing-in User's own memberships. There is deliberately no method that takes an
 * organization id as a parameter: the tenant comes from the token, never from a caller
 * (knowledge base ADR-0004).
 */
public interface MembershipRepository extends MongoRepository<Membership, UUID> {

    public List<Membership> findByOrganizationId(UUID organizationId);


    public List<Membership> findByUserId(UUID userId);

    public Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    public long countByOrganizationIdAndRole(UUID organizationId, Membership.Role role);

    public default Membership findOrThrow(UUID organizationId, UUID userId) {
        return findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}
