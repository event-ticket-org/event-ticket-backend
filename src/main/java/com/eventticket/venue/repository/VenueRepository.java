package com.eventticket.venue.repository;

import com.eventticket.shared.error.ApiException;
import com.eventticket.venue.domain.Venue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Row-level security has already narrowed these to the active Organization, plus any Venue
 * that a published Event has made public. The explicit {@code organizationId} on the listing
 * is therefore not the isolation - it is what keeps a manager's own list free of the other
 * organizations' public venues.
 */
public interface VenueRepository extends JpaRepository<Venue, UUID> {

    public List<Venue> findByOrganizationIdOrderByNameAsc(UUID organizationId);

    public List<Venue> findByIdIn(List<UUID> ids);

    @Query("select v.id from Venue v where lower(v.city) = lower(:city)")
    public List<UUID> findIdsByCity(@Param("city") String city);

    public default Venue findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Venue"));
    }
}
