package com.eventticket.identity.usecase;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.email.EmailSender;
import com.eventticket.shared.error.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.EmailVerificationToken;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.repository.EmailVerificationTokenRepository;
import com.eventticket.identity.security.SecureTokens;
import com.eventticket.organization.domain.Membership;

/** requirements/001 criterion 1. */
@Component
public class RegisterUser {

    private static final Logger log = LoggerFactory.getLogger(RegisterUser.class);

    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender email;
    private final String appBaseUrl;

    public RegisterUser(AppUserRepository users, EmailVerificationTokenRepository verificationTokens,
                 PasswordEncoder passwordEncoder, EmailSender email,
                 @Value("${app.base-url}") String appBaseUrl) {
        this.users = users;
        this.verificationTokens = verificationTokens;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public void register(String emailAddress, String password, String displayName) {
        AppUser existing = users.findByEmail(emailAddress).orElse(null);

        if (existing != null && existing.hasPassword()) {
            // Deliberately explicit rather than silently succeeding. This does disclose that
            // an address is registered, which is the accepted trade: an organizer who cannot
            // tell why signup failed raises a support ticket instead.
            throw new ApiException(ErrorCodes.ALREADY_EXISTS, "That email address is already registered.");
        }

        AppUser user;
        if (existing != null) {
            // Invited before they registered (criterion 8). The Membership an Owner created
            // is already attached to this row and becomes usable once the email is verified.
            existing.setPassword(displayName, passwordEncoder.encode(password));
            user = existing;
            log.info("Invited user completed registration userId={}", user.id());
        } else {
            user = users.save(new AppUser(emailAddress, displayName, passwordEncoder.encode(password)));
            log.info("Registered account userId={}", user.id());
        }

        sendVerification(user);
    }

    private void sendVerification(AppUser user) {
        String token = SecureTokens.issue();
        verificationTokens.save(new EmailVerificationToken(
                SecureTokens.hash(token), user.id(), Instant.now().plus(VERIFICATION_LIFETIME)));

        email.send(user.email(), "Confirm your email address",
                """
                Welcome to Event Ticketing.

                Confirm your email address to finish setting up your account:
                %s/verify-email?token=%s

                This link expires in 24 hours.
                """.formatted(appBaseUrl, token));
    }
}
