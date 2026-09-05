package com.eventticket.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    /** Callers hold the raw token; only its hash is stored. */
    default Optional<EmailVerificationToken> findByToken(String rawToken) {
        return findById(SecureTokens.hash(rawToken));
    }
}
