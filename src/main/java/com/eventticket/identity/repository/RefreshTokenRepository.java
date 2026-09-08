package com.eventticket.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.eventticket.identity.domain.RefreshTokenRecord;
import com.eventticket.identity.security.SecureTokens;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenRecord, String> {

    /** Callers hold the raw token; only its hash is stored. */
    public default Optional<RefreshTokenRecord> findByToken(String rawToken) {
        return findById(SecureTokens.hash(rawToken));
    }

    /**
     * {@code flushAutomatically} is not decoration. Clearing without flushing first discards
     * every pending change in the persistence context, so a caller that changes something and
     * then revokes - which is exactly what resetting a password does - silently loses the
     * change and commits a transaction that did half its work. It cost three failing tests to
     * find, and the failure looked like the password never having been set.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshTokenRecord t set t.revokedAt = :now "
            + "where t.userId = :userId and t.revokedAt is null")
    public void revokeAllFor(UUID userId, Instant now);
}
