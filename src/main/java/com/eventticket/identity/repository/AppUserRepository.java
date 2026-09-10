package com.eventticket.identity.repository;

import com.eventticket.shared.error.ApiException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.eventticket.identity.domain.AppUser;

public interface AppUserRepository extends MongoRepository<AppUser, UUID> {

    /** Email is the login identifier and is matched case-insensitively, as the index is. */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    public Optional<AppUser> findByEmail(String email);

    /**
     * Promotes accounts that already existed when the configuration named them. Registration
     * handles the ordinary case; this handles the address configured after the person signed
     * up, which is the usual way round when somebody is setting the system up locally.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update AppUser u
              set u.platformAdmin = true
            where lower(u.email) in :addresses and u.platformAdmin = false
           """)
    public int promoteToPlatformAdmin(Collection<String> addresses);

    public default AppUser findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }
}
