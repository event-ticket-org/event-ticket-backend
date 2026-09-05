package com.eventticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Me;
import com.eventticket.api.model.TokenPair;
import com.eventticket.identity.support.PlatformAdminBootstrap;
import com.eventticket.support.ApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Platform admin is granted from configuration and by nothing else.
 *
 * <p>Before this existed the flag had no writer at all, so no Organization could be approved
 * and every manager screen was unreachable. The rules worth holding are that configuration is
 * the only source, that it works in both orders - configured before signing up, and
 * configured afterwards - and that an ordinary account gains nothing by asking.
 */
class PlatformAdminTest extends ApiTest {

    @Autowired private PlatformAdminBootstrap bootstrap;

    @Test
    @DisplayName("a configured address is an administrator as soon as it registers")
    void configuredAddressIsPromotedOnRegistration() {
        TokenPair admin = signUp(PLATFORM_ADMIN_EMAIL);

        assertThat(me(admin).getPlatformAdmin()).isTrue();
    }

    @Test
    @DisplayName("any other address is not, however it registers")
    void otherAddressesAreNotAdministrators() {
        TokenPair ordinary = signUp("owner@example.com");

        assertThat(me(ordinary).getPlatformAdmin()).isFalse();
    }

    @Test
    @DisplayName("an account that registered before it was configured is promoted at startup")
    void existingAccountIsPromotedOnStartup() {
        TokenPair admin = signUp(PLATFORM_ADMIN_EMAIL);
        // The other order, and the usual one: somebody signs up, finds the manager screens
        // unreachable because nothing can approve their Organization, and adds their address
        // to the configuration. Clearing the column reproduces that account exactly.
        jdbc.update("update app_user set platform_admin = false where lower(email) = lower(?)",
                PLATFORM_ADMIN_EMAIL);
        assertThat(me(admin).getPlatformAdmin()).isFalse();

        bootstrap.promoteConfiguredAdmins();

        assertThat(me(admin).getPlatformAdmin()).isTrue();
    }

    private Me me(TokenPair session) {
        return exchange(org.springframework.http.HttpMethod.GET, "/me", session, null, Me.class)
                .getBody();
    }
}
