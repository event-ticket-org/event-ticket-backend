package com.eventticket.identity.usecase;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.Session;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.security.SessionIssuer;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;

/**
 * requirements/001. Signing in produces a session with no active Organization; the client
 * reads {@code GET /me} and calls {@code POST /auth/switch-organization} to choose one.
 *
 * <p>An earlier draft selected the Organization here when the User held exactly one
 * Membership. It could not work, and the reason is worth keeping: at this point in the
 * request nobody is authenticated, so {@code current_app_user_id()} is null and the
 * membership policy correctly matches no rows. Row-level security refused a read that was
 * not yet scoped to anyone - which is the behaviour ADR-0004 asks for, arriving as a
 * refusal rather than as a leak.
 */
@Component
public class Login {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionIssuer sessions;

    public Login(AppUserRepository users, PasswordEncoder passwordEncoder, SessionIssuer sessions) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
    }

    @Transactional
    public Session login(String emailAddress, String password) {
        AppUser user = users.findByEmail(emailAddress)
                // hasPassword guards an invited User who has not registered yet: their hash
                // is null, and there is no password that can match it.
                .filter(AppUser::hasPassword)
                .filter(u -> passwordEncoder.matches(password, u.passwordHash()))
                // One message for an unknown address and for a wrong password: distinguishing
                // them turns this endpoint into a way to enumerate registered addresses.
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_AUTHENTICATED,
                        "Email address or password is incorrect."));

        return sessions.issueFor(user, null);
    }
}
