package com.eventticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.ForgotPasswordRequest;
import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.LoginRequest;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.RefreshRequest;
import com.eventticket.api.model.RegisterRequest;
import com.eventticket.api.model.ResetPasswordRequest;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.TokenPair;
import com.eventticket.identity.support.ResetRequestLimiter;
import com.eventticket.support.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * requirements/001 criteria 17-21.
 *
 * <p>Driven over HTTP and through the emails a person would actually receive, rather than by
 * reading the token table: the link is the product, and a test that reaches past it would pass
 * against a system that never sent one.
 */
class PasswordResetTest extends ApiTest {

    private static final String OLD_PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "a-different-long-password";

    @Autowired private ResetRequestLimiter limiter;

    @BeforeEach
    void forgetLimits() {
        // The limiter is in memory and outlives a truncate, so without this the address a test
        // reuses arrives already throttled by the test before it.
        limiter.forget("forgetful@example.com");
        limiter.forget("nobody@example.com");
        limiter.forget("unverified@example.com");
        limiter.forget("invited@example.com");
    }

    private void requestReset(String address) {
        var response = http.postForEntity("/auth/forgot-password",
                new ForgotPasswordRequest(address), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private org.springframework.http.ResponseEntity<TokenPair> resetWith(String token, String password) {
        return http.postForEntity("/auth/reset-password",
                new ResetPasswordRequest(token, password), TokenPair.class);
    }

    @Test
    @DisplayName("a forgotten password is replaced by one that works, and the old one stops")
    void aForgottenPasswordCanBeReset() {
        signUp("forgetful@example.com");
        requestReset("forgetful@example.com");

        String token = email.resetTokenFor("forgetful@example.com")
                .orElseThrow(() -> new AssertionError("no reset email was sent"));
        var reset = resetWith(token, NEW_PASSWORD);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().getAccessToken()).isNotBlank();

        var withOld = http.postForEntity("/auth/login",
                new LoginRequest("forgetful@example.com", OLD_PASSWORD), String.class);
        assertThat(withOld.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var withNew = http.postForEntity("/auth/login",
                new LoginRequest("forgetful@example.com", NEW_PASSWORD), TokenPair.class);
        assertThat(withNew.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Criterion 17. The assertion that matters is the second one: an endpoint answering 202 to
     * everything still reports who has an account if it only sends mail to some of them and
     * anybody can measure the difference. Here the observable behaviour is identical, and the
     * only thing that differs is a log line.
     */
    @Test
    @DisplayName("an address with no account gets the same answer and no email")
    void theAnswerIsTheSameForAnAddressWithNoAccount() {
        requestReset("nobody@example.com");
        assertThat(email.to("nobody@example.com")).isEmpty();
    }

    /**
     * Criterion 18, and the dead end it exists to close: this account never followed its
     * verification link, so before this change it had no route back at all.
     */
    @Test
    @DisplayName("resetting verifies an address that was never verified")
    void aResetVerifiesTheAddress() {
        http.postForEntity("/auth/register",
                new RegisterRequest("unverified@example.com", OLD_PASSWORD, "Never Verified"),
                Void.class);
        email.clear();

        requestReset("unverified@example.com");
        String token = email.resetTokenFor("unverified@example.com").orElseThrow();
        TokenPair session = resetWith(token, NEW_PASSWORD).getBody();

        Me me = exchange(HttpMethod.GET, "/me", session, null, Me.class).getBody();
        assertThat(me.getEmailVerified()).isTrue();
    }

    /**
     * Criterion 19. Proved through the refresh token rather than the access token, because an
     * access token is a signature with a lifetime and revoking it is not what happens - ADR-0005
     * puts revocation at refresh, so refresh is where the other session actually dies.
     */
    @Test
    @DisplayName("a reset ends every other session, and keeps the one doing the resetting")
    void aResetEndsEveryOtherSession() {
        signUp("forgetful@example.com");
        TokenPair elsewhere = signIn("forgetful@example.com");

        requestReset("forgetful@example.com");
        TokenPair mine = resetWith(email.resetTokenFor("forgetful@example.com").orElseThrow(),
                NEW_PASSWORD).getBody();

        var theirRefresh = http.postForEntity("/auth/refresh",
                new RefreshRequest(elsewhere.getRefreshToken()), String.class);
        assertThat(theirRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var myRefresh = http.postForEntity("/auth/refresh",
                new RefreshRequest(mine.getRefreshToken()), TokenPair.class);
        assertThat(myRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Criterion 20: the notice is the only thing that tells a real owner it was not them. */
    @Test
    @DisplayName("the address is told the password changed")
    void theAddressIsToldThePasswordChanged() {
        signUp("forgetful@example.com");
        requestReset("forgetful@example.com");
        resetWith(email.resetTokenFor("forgetful@example.com").orElseThrow(), NEW_PASSWORD);

        assertThat(email.to("forgetful@example.com"))
                .anyMatch(m -> m.subject().equals("Your password was changed"));
    }

    /** Criterion 21, first half. 410 rather than 404: the link was real and is now spent. */
    @Test
    @DisplayName("a link works once")
    void aLinkIsUsableOnce() {
        signUp("forgetful@example.com");
        requestReset("forgetful@example.com");
        String token = email.resetTokenFor("forgetful@example.com").orElseThrow();

        assertThat(resetWith(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        var second = http.postForEntity("/auth/reset-password",
                new ResetPasswordRequest(token, "yet-another-long-password"), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    /**
     * Criterion 21, second half. This is the one that protects a forwarded message: asking
     * again is what somebody does when the first email did not arrive, and until it ends the
     * earlier link both are live and the older one is the one somebody else may be holding.
     */
    @Test
    @DisplayName("asking for a new link ends the previous one")
    void askingAgainEndsTheEarlierLink() {
        signUp("forgetful@example.com");
        requestReset("forgetful@example.com");
        String first = email.resetTokenFor("forgetful@example.com").orElseThrow();

        requestReset("forgetful@example.com");
        String second = email.resetTokenFor("forgetful@example.com").orElseThrow();
        assertThat(second).isNotEqualTo(first);

        var stale = http.postForEntity("/auth/reset-password",
                new ResetPasswordRequest(first, NEW_PASSWORD), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(resetWith(second, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** nfr.md: the endpoint is unauthenticated and sends mail to an address the caller names. */
    @Test
    @DisplayName("a mailbox cannot be buried in reset emails")
    void tooManyRequestsAreRefused() {
        signUp("forgetful@example.com");

        HttpStatus last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            last = HttpStatus.valueOf(http.postForEntity("/auth/forgot-password",
                    new ForgotPasswordRequest("forgetful@example.com"), String.class)
                    .getStatusCode().value());
            if (last == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
        }
        assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(email.to("forgetful@example.com")).hasSizeLessThan(10);
    }

    /**
     * An invited User who never registered has a row and no password, and registration is the
     * route that finishes them properly - it is where the display name comes from. A reset
     * would set a password and leave the account half-made, so this address is treated the way
     * an unknown one is.
     */
    @Test
    @DisplayName("an invitation that was never accepted is not an account to recover")
    void anInvitedUserWhoNeverRegisteredGetsNoLink() {
        TokenPair owner = signUp("forgetful@example.com");
        var organization = createOrganization(owner, "Invites");
        approve(organization);
        owner = switchTo(owner, organization);
        exchange(HttpMethod.POST, "/organization/members", owner,
                new InviteMemberRequest("invited@example.com", Role.MANAGER), String.class);
        email.clear();

        requestReset("invited@example.com");
        assertThat(email.to("invited@example.com")).isEmpty();
    }
}
