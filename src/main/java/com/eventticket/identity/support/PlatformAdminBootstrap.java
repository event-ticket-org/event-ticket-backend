package com.eventticket.identity.support;

import com.eventticket.identity.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link ConfiguredPlatformAdmins} to accounts that already exist.
 *
 * <p>Registration covers the address configured before the person signs up. This covers the
 * other order, which is the usual one when somebody is setting the system up: register, find
 * the manager screens unreachable because no Organization can be approved, add the address to
 * the configuration, restart.
 *
 * <p>Idempotent by construction - the update matches only rows that are not already admins -
 * so it costs one statement per boot and says nothing when there is nothing to do.
 */
@Component
public class PlatformAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final AppUserRepository users;
    private final ConfiguredPlatformAdmins platformAdmins;

    public PlatformAdminBootstrap(AppUserRepository users, ConfiguredPlatformAdmins platformAdmins) {
        this.users = users;
        this.platformAdmins = platformAdmins;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promoteConfiguredAdmins() {
        if (platformAdmins.addresses().isEmpty()) {
            return;
        }
        int promoted = users.promoteToPlatformAdmin(platformAdmins.addresses());
        if (promoted > 0) {
            log.info("Promoted {} existing account(s) to platform admin from configuration", promoted);
        }
    }
}
