package com.eventticket.shared.audit;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.eventticket.shared.tenancy.TenantContext;

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
    // requirements/003 criterion 15: publishing, price changes, start-time changes and
    // listing changes are all auditable events in their own right.
    public static final String EVENT_PUBLISHED = "EVENT_PUBLISHED";
    public static final String EVENT_PRICES_CHANGED = "EVENT_PRICES_CHANGED";
    public static final String EVENT_START_TIME_CHANGED = "EVENT_START_TIME_CHANGED";
    public static final String EVENT_LISTING_CHANGED = "EVENT_LISTING_CHANGED";
    public static final String EVENT_SALES_CLOSED = "EVENT_SALES_CLOSED";

    private final AuditEntryRepository entries;

    public AuditTrail(AuditEntryRepository entries) {
        this.entries = entries;
    }

    public void record(UUID organizationId, String action, String subject) {
        entries.save(new AuditEntry(organizationId, TenantContext.userId(), action, subject));
    }
}
