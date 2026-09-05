package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criterion 12. Closing sales stops new Orders and nothing else: Tickets
 * already sold stay valid and scannable, which is why this is a status change and never a
 * change to a Ticket.
 */
@Component
public class CloseSales {

    private static final Logger log = LoggerFactory.getLogger(CloseSales.class);

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final Managers managers;
    private final AuditTrail audit;

    public CloseSales(EventRepository events, PricingTierRepository tiers,
               Managers managers, AuditTrail audit) {
        this.events = events;
        this.tiers = tiers;
        this.managers = managers;
        this.audit = audit;
    }

    @Transactional
    public EventDetail close(UUID eventId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        event.closeSales();
        events.save(event);

        audit.record(organizationId, AuditTrail.EVENT_SALES_CLOSED, event.title());
        log.info("Closed sales eventId={}", eventId);

        return EventDetail.of(event, EventPricing.of(event, null, tiers.findByEventId(eventId)));
    }
}
