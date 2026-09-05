package com.eventticket.identity.usecase;

import com.eventticket.organization.repository.MembershipRepository;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantPublisher;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.domain.RefreshTokenRecord;
import com.eventticket.identity.domain.Session;
import com.eventticket.identity.repository.AppUserRepository;
import com.eventticket.identity.repository.RefreshTokenRepository;
import com.eventticket.identity.security.SessionIssuer;
import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;

/**
 * Exchanges a refresh token for a new access token, and is where revocation bites
 * (knowledge base ADR-0005).
 *
 * <p>The active Organization is re-checked against the database rather than carried over
 * from the old token: a User whose Membership was removed while their access token was still
 * valid loses the Organization here, at most fifteen minutes later. The old refresh token is
 * revoked and a new one issued, so a stolen refresh token stops working the moment the
 * legitimate holder uses theirs.
 */
@Component
public class RefreshSession {

    private static final Logger log = LoggerFactory.getLogger(RefreshSession.class);

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final SessionIssuer sessions;
    private final TenantPublisher tenant;

    public RefreshSession(AppUserRepository users, MembershipRepository memberships,
                   RefreshTokenRepository refreshTokens, SessionIssuer sessions,
                   TenantPublisher tenant) {
        this.users = users;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.tenant = tenant;
    }

    @Transactional
    public Session refresh(String rawToken) {
        Instant now = Instant.now();

        RefreshTokenRecord record = refreshTokens.findByToken(rawToken)
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> {
                    log.warn("Refresh rejected: token unknown, expired or already revoked");
                    return new ApiException(ErrorCodes.NOT_AUTHENTICATED,
                            "That session has expired. Sign in again.");
                });

        record.revoke(now);

        AppUser user = users.findOrThrow(record.userId());
        return sessions.issueFor(user, stillAMemberOf(record.activeOrganizationId(), user.id()));
    }

    /**
     * The Membership is read with the User established as the tenant subject, which is what
     * lets the row-level security policy's own-memberships branch match. The transaction is
     * already open here, so the identity has to be published to the live database session -
     * setting it in the JVM alone would leave Postgres still looking at the empty tenant it
     * was given at begin, and every refresh would silently drop the active Organization.
     */
    private UUID stillAMemberOf(UUID organizationId, UUID userId) {
        if (organizationId == null) {
            return null;
        }
        tenant.adopt(userId, organizationId);
        boolean stillAMember = memberships.findByOrganizationIdAndUserId(organizationId, userId).isPresent();
        if (!stillAMember) {
            // The visible effect of ADR-0005's revocation window closing. Worth a line: it is
            // how "why did my access disappear mid-shift?" gets answered.
            log.info("Membership no longer held; dropping active organization userId={} organizationId={}",
                    userId, organizationId);
        }
        return stillAMember ? organizationId : null;
    }
}
