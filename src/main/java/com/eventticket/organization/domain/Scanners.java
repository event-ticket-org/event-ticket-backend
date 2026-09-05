package com.eventticket.organization.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.eventticket.organization.repository.MembershipRepository;

/**
 * Answers "may this caller open a door for the Organization?".
 *
 * <p>The third of the three powers, beside {@link Owners} and {@link Managers}, and the widest:
 * every role scans. Gate Staff hold this one alone, which is the whole reason the role exists.
 *
 * <p>Asked of live Membership on every scan, never of a token claim - KB invariant 17 and
 * requirements/007 criterion 8. Somebody removed from the door staff stops being able to admit
 * people on their next scan, not fifteen minutes later when their access token expires. That
 * window is acceptable for reading a page and not for opening a gate.
 */
@Component
public class Scanners {

    private static final Logger log = LoggerFactory.getLogger(Scanners.class);

    private final MembershipRepository memberships;

    public Scanners(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public Membership requireCallerCanScan(UUID organizationId) {
        UUID userId = TenantContext.requireUserId();
        return memberships.findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> {
                    log.warn("Scan refused: userId={} has no membership in organizationId={}",
                            userId, organizationId);
                    return ApiException.notPermitted(
                            "You are not on the door staff for this event's organization.");
                });
    }
}
