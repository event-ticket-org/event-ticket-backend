package com.eventticket.identity.repository;

import com.eventticket.identity.domain.AppUser;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.mongo.Collations;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface AppUserRepository extends MongoRepository<AppUser, UUID> {

    /**
     * Email is the login identifier and is matched case-insensitively, as the index is.
     *
     * <p>Where JPQL said {@code lower(u.email) = lower(:email)}, the comparison here is an
     * ordinary equality and the folding is the collation's job. The index in
     * {@code MongoIndexes} is built with the same collation, or this query would not use it -
     * a mismatch costs a collection scan and no error.
     */
    @Query(value = "{ 'email': ?0 }", collation = Collations.CASE_INSENSITIVE)
    public Optional<AppUser> findByEmail(String email);

    /**
     * Promotes accounts that already existed when the configuration named them. Registration
     * handles the ordinary case; this handles the address configured after the person signed
     * up, which is the usual way round when somebody is setting the system up locally.
     *
     * <p>The JPA version carried {@code @Modifying(clearAutomatically = true,
     * flushAutomatically = true)}, and that pairing was not decoration - omitting the flush
     * discarded every pending change in the persistence context, which cost three failing tests
     * to find in #27. <strong>There is no equivalent to get wrong here.</strong> A MongoDB
     * update is issued to the server when it is called; there is no context holding unwritten
     * work, so there is nothing to flush and nothing to clear. An entire class of bug leaves
     * with JPA.
     */
    @Query(value = "{ 'email': { '$in': ?0 }, 'platformAdmin': false }",
           collation = Collations.CASE_INSENSITIVE)
    @Update("{ '$set': { 'platformAdmin': true } }")
    public int promoteToPlatformAdmin(Collection<String> addresses);

    public default AppUser findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }
}
