package com.eventticket.identity.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.eventticket.identity.domain.EmailVerificationToken;
import com.eventticket.identity.security.SecureTokens;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    /** Callers hold the raw token; only its hash is stored. */
    public default Optional<EmailVerificationToken> findByToken(String rawToken) {
        return findById(SecureTokens.hash(rawToken));
    }
}
