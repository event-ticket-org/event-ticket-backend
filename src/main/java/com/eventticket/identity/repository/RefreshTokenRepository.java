package com.eventticket.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import com.eventticket.identity.domain.RefreshTokenRecord;
import com.eventticket.identity.security.SecureTokens;

public interface RefreshTokenRepository extends MongoRepository<RefreshTokenRecord, String> {

    /** Callers hold the raw token; only its hash is stored. */
    public default Optional<RefreshTokenRecord> findByToken(String rawToken) {
        return findById(SecureTokens.hash(rawToken));
    }

    /**
     * The JPA version needed {@code @Modifying(clearAutomatically = true, flushAutomatically =
     * true)}, and the pairing was load-bearing: clearing without flushing first threw away the
     * new password that {@code ResetPassword} had just set, and it took three failing tests to
     * find because the symptom looked like the reset never happening.
     *
     * <p>That trap does not exist here. The update is sent when it is called - there is no
     * persistence context holding unwritten changes, so there is nothing to flush before it and
     * nothing to clear after it.
     */
    @Query("{ 'userId': ?0, 'revokedAt': null }")
    @Update("{ '$set': { 'revokedAt': ?1 } }")
    public void revokeAllFor(UUID userId, Instant now);
}
