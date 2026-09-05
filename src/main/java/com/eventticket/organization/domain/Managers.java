package com.eventticket.organization.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.eventticket.organization.repository.MembershipRepository;

/**
 * Answers "may this caller build and sell events for the Organization?".
 *
 * <p>The sibling of {@link Owners}, and separate from it on purpose: managing people and
 * managing events are different powers. An Owner holds both; a Manager holds only this one;
 * Gate Staff hold neither (KB invariant 3).
 *
 * <p>Asked of live Membership rather than of a token claim, so that a demotion takes effect
 * on the next request rather than at the next token refresh.
 */
@Component
public class Managers {

    private static final Logger log = LoggerFactory.getLogger(Managers.class);

    private final MembershipRepository memberships;

    public Managers(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public Membership requireCallerCanManageEvents(UUID organizationId) {
        Membership caller = memberships.findOrThrow(organizationId, TenantContext.requireUserId());
        if (caller.role() == Membership.Role.GATE_STAFF) {
            log.warn("Event management refused: caller is {} organizationId={}",
                    caller.role(), organizationId);
            throw ApiException.notPermitted("Only an owner or a manager can manage venues and events.");
        }
        return caller;
    }
}
