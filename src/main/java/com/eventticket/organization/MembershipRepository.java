package com.eventticket.organization;

import com.eventticket.shared.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads here are already narrowed by row-level security to the active Organization, plus the
 * signing-in User's own memberships. There is deliberately no method that takes an
 * organization id as a parameter: the tenant comes from the token, never from a caller
 * (knowledge base ADR-0004).
 */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByOrganizationId(UUID organizationId);

    List<Membership> findByUserId(UUID userId);

    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    long countByOrganizationIdAndRole(UUID organizationId, Membership.Role role);

    default Membership findOrThrow(UUID organizationId, UUID userId) {
        return findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}
