package com.eventticket.identity.usecase;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.mongo.TransientRetry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <h2>Why the transaction is retried</h2>
 *
 * <p>One link, opened twice at once, is the ordinary case rather than the exotic one: React's
 * development mode fires the effect twice, and a double-click, a prefetching mail client or a
 * browser retry all do the same in production. Running this behind the real frontend produced
 * two requests five milliseconds apart - one {@code Verified email}, and one
 * {@code WriteConflict (112)} that reached the browser as a 500.
 *
 * <p>Postgres serialised them: the loser blocked on the token row, woke, re-read it, found it
 * consumed and answered 410. MongoDB aborts rather than waits, so the loser has to be run
 * again - and on the second run the token is genuinely spent, so {@code isUsable} produces the
 * same 410 by the ordinary domain rule. The retry does not paper over the race; it gives the
 * loser the chance to observe it that a row lock used to give for free.
 */
@Component
public class VerifyEmail {

    private static final Logger log = LoggerFactory.getLogger(VerifyEmail.class);

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final SessionIssuer sessions;
    private final TransientRetry retry;
    private final TransactionTemplate transactions;

    public VerifyEmail(AppUserRepository users, EmailVerificationTokenRepository tokens,
                       SessionIssuer sessions, TransientRetry retry,
                       PlatformTransactionManager transactionManager) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.retry = retry;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Session verify(String rawToken) {
        return retry.execute(() -> transactions.execute(status -> attempt(rawToken)));
    }

    private Session attempt(String rawToken) {
        Instant now = Instant.now();

        EmailVerificationToken token = tokens.findByToken(rawToken)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> {
                    log.warn("Email verification refused: token expired, unknown or already used");
                    return ApiException.gone(
                            "That verification link has expired or has already been used.");
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
        user.markEmailVerified();
        users.save(user);

        log.info("Verified email userId={}", user.id());
        return sessions.issueFor(user, null);
    }
}
