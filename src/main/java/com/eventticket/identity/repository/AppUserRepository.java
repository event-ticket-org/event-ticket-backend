package com.eventticket.identity.repository;

import com.eventticket.shared.error.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.eventticket.identity.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Email is the login identifier and is matched case-insensitively, as the index is. */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    public Optional<AppUser> findByEmail(String email);

    public default AppUser findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }
}
