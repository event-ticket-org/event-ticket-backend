package com.eventticket.organization;

import com.eventticket.shared.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    List<Organization> findByStatusOrderByCreatedAtDesc(Organization.Status status, Pageable pageable);

    List<Organization> findAllByOrderByCreatedAtDesc(Pageable pageable);

    default Organization findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Organization"));
    }
}
