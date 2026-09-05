package com.eventticket.identity;

import com.eventticket.shared.ApiException;
import com.eventticket.shared.ErrorCodes;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criteria 1 and 8. Verifying signs the User in, so that someone who has
 * just proved they own the address is not immediately asked to log in.
 *
 * <p>A Membership created by an invitation before the invitee had an account becomes usable
 * at this point: it was always attached to the User row, and the verification is what makes
 * the User able to act (criterion 8).
 */
@Component
class VerifyEmail {

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final SessionIssuer sessions;

    VerifyEmail(AppUserRepository users, EmailVerificationTokenRepository tokens, SessionIssuer sessions) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Transactional
    Session verify(String rawToken) {
        Instant now = Instant.now();

        EmailVerificationToken token = tokens.findByToken(rawToken)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND,
                        "That verification link has expired or has already been used."));

        token.consume(now);

        AppUser user = users.findOrThrow(token.userId());
        user.markEmailVerified();

        return sessions.issueFor(user, null);
    }
}
