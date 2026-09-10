package com.eventticket.identity.usecase;

import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.PasswordResetToken;
import com.eventticket.identity.domain.Session;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.repository.PasswordResetTokenRepository;
import com.eventticket.identity.repository.RefreshTokenRepository;
import com.eventticket.identity.security.SessionIssuer;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.error.ApiException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/001 criteria 18, 19 and 20.
 *
 * <p>Three things happen together and none of them is optional.
 *
 * <p><strong>The address is marked verified.</strong> Reading the mailbox is the same proof
 * registration asks for, so a reset that did not verify would leave somebody who has just
 * proved they own the address still unable to act. It also closes the only dead end this system
 * had: a User whose verification link expired has no other route back, and this way recovering
 * the password recovers the account rather than a second mechanism existing to recover the
 * verification.
 *
 * <p><strong>Every other session ends.</strong> The common reason to reset is that somebody
 * else has the old password, and a reset that left their session alive would fix nothing. The
 * order matters: revoke first and issue afterwards, or the session handed back here is revoked
 * along with the rest.
 *
 * <p><strong>The address is told.</strong> That notice is the only thing that tells a real
 * owner a reset happened when it was not them. It is worth sending even though the account has
 * already changed by then - it is not a confirmation, it is what makes the next step possible.
 */
@Component
public class ResetPassword {

    private static final Logger log = LoggerFactory.getLogger(ResetPassword.class);

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final SessionIssuer sessions;
    private final EmailSender email;

    public ResetPassword(AppUserRepository users, PasswordResetTokenRepository tokens,
                  RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                  SessionIssuer sessions, EmailSender email) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.email = email;
    }

    @Transactional
    public Session reset(String rawToken, String newPassword) {
        Instant now = Instant.now();

        PasswordResetToken token = tokens.findByToken(rawToken)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> {
                    log.warn("Password reset refused: link expired, unknown, already used or superseded");
                    return ApiException.gone(
                            "That reset link has expired or has already been used. Ask for a new one.");
                });

        // Explicit saves, because there is no dirty checking here.
        //
        // Under JPA a loaded entity is managed: mutating it was enough, and the flush at commit
        // wrote the change. MongoDB has no persistence context and no managed state, so an
        // object loaded, mutated and not saved is an object that was never changed. Nothing
        // warns, nothing fails, and the transaction commits successfully having done nothing.
        token.consume(now);
        tokens.save(token);

        AppUser user = users.findOrThrow(token.userId());
        user.changePassword(passwordEncoder.encode(newPassword));
        user.markEmailVerified();
        users.save(user);

        refreshTokens.revokeAllFor(user.id(), now);

        email.send(user.email(), "Your password was changed",
                """
                The password for this account has just been changed, and everywhere it was
                signed in has been signed out.

                If that was you, there is nothing to do.

                If it was not, somebody has read this mailbox. Reset the password again to take
                the account back, and change the password on the mailbox itself - that is the
                one that matters, because it is what let them in.
                """);

        log.info("Password reset completed userId={}; all other sessions revoked", user.id());
        return sessions.issueFor(user, null);
    }
}
