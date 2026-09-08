package com.eventticket.identity.usecase;

import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.PasswordResetToken;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.repository.PasswordResetTokenRepository;
import com.eventticket.identity.security.SecureTokens;
import com.eventticket.identity.support.ResetRequestLimiter;
import com.eventticket.shared.email.EmailSender;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criterion 17: ask for a reset link, and be told the same thing either way.
 *
 * <p><strong>Every path through this class returns normally.</strong> An unknown address, an
 * address that was invited and never registered, and a real account all produce the same
 * answer, because the form is public and unauthenticated: an answer that distinguished them
 * would turn it into a way to find out who has an account here, and a list of this platform's
 * buyers is worth stealing on its own. The only refusal is the rate limit, and that is checked
 * before the address is looked up so it cannot become the same oracle by a different route.
 *
 * <p>An invited User with no password is treated as no account (criterion 8 owns that case).
 * A reset would set their password and leave them with no display name, which is registration
 * done badly - and the invitation email they already have is the route that does it properly.
 */
@Component
public class RequestPasswordReset {

    private static final Logger log = LoggerFactory.getLogger(RequestPasswordReset.class);

    /**
     * nfr.md: an hour, where email verification's link lasts a day. The difference is what the
     * link does - a verification link makes a new account usable and its owner is waiting for
     * it, while this one opens an account that already exists, already holds orders and
     * tickets, and may already be the subject of whatever prompted the reset.
     */
    private static final Duration RESET_LIFETIME = Duration.ofHours(1);

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final EmailSender email;
    private final ResetRequestLimiter limiter;
    private final String appBaseUrl;

    public RequestPasswordReset(AppUserRepository users, PasswordResetTokenRepository tokens,
                         EmailSender email, ResetRequestLimiter limiter,
                         @Value("${app.base-url}") String appBaseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.email = email;
        this.limiter = limiter;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public void request(String emailAddress) {
        limiter.requireWithinLimit(emailAddress);

        AppUser user = users.findByEmail(emailAddress).filter(AppUser::hasPassword).orElse(null);
        if (user == null) {
            // INFO and not WARN. Somebody mistyping their own address is the ordinary case, and
            // this is the line that explains a support question about an email that never came.
            log.info("Password reset requested for an address with no account");
            return;
        }

        // Criterion 21: a new request ends the outstanding ones, so a link forwarded or read
        // over a shoulder stops working the moment its owner asks for another.
        tokens.consumeAllFor(user.id(), Instant.now());

        String token = SecureTokens.issue();
        tokens.save(new PasswordResetToken(
                SecureTokens.hash(token), user.id(), Instant.now().plus(RESET_LIFETIME)));

        email.send(user.email(), "Reset your password",
                """
                Somebody asked to reset the password for this address.

                Set a new one here:
                %s/reset-password?token=%s

                This link expires in one hour and can be used once. If it was not you, you can
                ignore this - your password has not changed, and nobody can read this message
                but you.
                """.formatted(appBaseUrl, token));

        log.info("Password reset link sent userId={}", user.id());
    }
}
