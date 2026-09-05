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

    @Modifying(clearAutomatically = true)
    @Query("update RefreshTokenRecord t set t.revokedAt = :now "
            + "where t.userId = :userId and t.revokedAt is null")
    public void revokeAllFor(UUID userId, Instant now);
}
