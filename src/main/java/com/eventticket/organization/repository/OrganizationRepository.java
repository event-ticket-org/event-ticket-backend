package com.eventticket.organization.repository;

import com.eventticket.shared.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.eventticket.organization.domain.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    public List<Organization> findByStatusOrderByCreatedAtDesc(Organization.Status status, Pageable pageable);

    public List<Organization> findAllByOrderByCreatedAtDesc(Pageable pageable);

    public default Organization findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Organization"));
    }
}
