package com.eventticket.venue.repository;

import com.eventticket.shared.error.ApiException;
import com.eventticket.venue.domain.Venue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.eventticket.shared.mongo.Collations;
import org.springframework.data.mongodb.repository.Query;

/**
 * Row-level security has already narrowed these to the active Organization, plus any Venue
 * that a published Event has made public. The explicit {@code organizationId} on the listing
 * is therefore not the isolation - it is what keeps a manager's own list free of the other
 * organizations' public venues.
 */
public interface VenueRepository extends MongoRepository<Venue, UUID> {

    public List<Venue> findByOrganizationIdOrderByNameAsc(UUID organizationId);

    public List<Venue> findByIdIn(List<UUID> ids);

    /**
     * {@code fields} is the projection: only the id comes back over the wire, which is what
     * {@code select v.id} meant. The mapping to UUIDs happens here rather than in the caller so
     * the signature the rest of the application depends on does not change - the point of this
     * migration is that nothing above the repository can tell.
     */
    @Query(value = "{ 'city': ?0 }", fields = "{ '_id': 1 }",
           collation = Collations.CASE_INSENSITIVE)
    public List<Venue> findIdsByCityInternal(String city);

    public default List<UUID> findIdsByCity(String city) {
        return findIdsByCityInternal(city).stream().map(Venue::id).toList();
    }

    public default Venue findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Venue"));
    }
}
