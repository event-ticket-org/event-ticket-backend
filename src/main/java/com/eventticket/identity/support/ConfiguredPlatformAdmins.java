package com.eventticket.identity.support;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who the platform administrators are, according to configuration.
 *
 * <p>Platform admin is the one privilege no API can grant, and deliberately so: the endpoint
 * that approves Organizations decides who may sell tickets at all, so a request that could
 * confer it would be the most valuable request in the system. It comes from the environment
 * instead, which means granting it requires access to the deployment rather than to the
 * application.
 *
 * <p>Before this existed, nothing set the flag at all - no endpoint, no seed, no
 * documentation - so no Organization could ever be approved and the manager screens were
 * unreachable. The test suite had to reach past the application and write the column with
 * SQL, which is the clearest evidence that the mechanism was missing rather than merely
 * undocumented.
 *
 * <p>Named for where the grant comes from, because {@code platform.support.PlatformAdmins}
 * is the other half of this and does the opposite job: this decides who is an administrator,
 * that one refuses a caller who is not.
 *
 * <p>Matching is case-insensitive on the whole address, consistent with
 * {@code app_user_email_key} being a unique index on {@code lower(email)}.
 */
@Component
public class ConfiguredPlatformAdmins {

    private final Set<String> addresses;

    public ConfiguredPlatformAdmins(@Value("${app.platform-admin-emails:}") String configured) {
        this.addresses = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .map(address -> address.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean includes(String email) {
        return email != null && addresses.contains(email.toLowerCase(Locale.ROOT));
    }

    public Set<String> addresses() {
        return addresses;
    }
}
