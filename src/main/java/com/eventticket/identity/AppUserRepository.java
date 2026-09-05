package com.eventticket.identity;

import com.eventticket.shared.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Email is the login identifier and is matched case-insensitively, as the index is. */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmail(String email);

    default AppUser findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }
}
