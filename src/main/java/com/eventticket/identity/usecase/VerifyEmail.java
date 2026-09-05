package com.eventticket.identity.usecase;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.EmailVerificationToken;
import com.eventticket.identity.domain.Session;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.repository.EmailVerificationTokenRepository;
import com.eventticket.identity.security.SessionIssuer;
import com.eventticket.organization.domain.Membership;

/**
 * requirements/001 criteria 1 and 8. Verifying signs the User in, so that someone who has
 * just proved they own the address is not immediately asked to log in.
 *
 * <p>A Membership created by an invitation before the invitee had an account becomes usable
 * at this point: it was always attached to the User row, and the verification is what makes
 * the User able to act (criterion 8).
 */
@Component
public class VerifyEmail {

    private static final Logger log = LoggerFactory.getLogger(VerifyEmail.class);

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final SessionIssuer sessions;

    public VerifyEmail(AppUserRepository users, EmailVerificationTokenRepository tokens, SessionIssuer sessions) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Transactional
    public Session verify(String rawToken) {
        Instant now = Instant.now();

        EmailVerificationToken token = tokens.findByToken(rawToken)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> {
                    log.warn("Email verification refused: token expired, unknown or already used");
                    return new ApiException(ErrorCodes.NOT_FOUND,
                            "That verification link has expired or has already been used.");
                });

        token.consume(now);

        AppUser user = users.findOrThrow(token.userId());
        user.markEmailVerified();

        log.info("Verified email userId={}", user.id());
        return sessions.issueFor(user, null);
    }
}
