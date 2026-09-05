package com.eventticket.shared;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Records who did what. Knowledge base invariant 23 and requirements/001 criterion 12.
 *
 * <p>Called from within a use case's transaction, so an audit entry and the change it
 * describes commit or roll back together - a trail that can disagree with the data it
 * describes is worse than none.
 */
@Component
public class AuditTrail {

    /** Actions are named for what happened, and are matched on in tests. */
    public static final String MEMBER_INVITED = "MEMBER_INVITED";
    public static final String MEMBER_ROLE_CHANGED = "MEMBER_ROLE_CHANGED";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";
    public static final String ORGANIZATION_APPROVED = "ORGANIZATION_APPROVED";
    public static final String ORGANIZATION_REJECTED = "ORGANIZATION_REJECTED";

    private final AuditEntryRepository entries;

    AuditTrail(AuditEntryRepository entries) {
        this.entries = entries;
    }

    public void record(UUID organizationId, String action, String subject) {
        entries.save(new AuditEntry(organizationId, TenantContext.userId(), action, subject));
    }
}
