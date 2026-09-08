package com.eventticket.identity.repository;

import com.eventticket.identity.domain.PasswordResetToken;
import com.eventticket.identity.security.SecureTokens;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    /** Callers hold the raw token; only its hash is stored. */
    public default Optional<PasswordResetToken> findByToken(String rawToken) {
        return findById(SecureTokens.hash(rawToken));
    }

    /**
     * requirements/001 criterion 21. Consuming rather than deleting, so a link that stops
     * working leaves a record that it was superseded rather than vanishing - which is the
     * difference between answering "that link is spent" and "that link never existed".
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.consumedAt = :now "
            + "where t.userId = :userId and t.consumedAt is null")
    public void consumeAllFor(UUID userId, Instant now);
}
