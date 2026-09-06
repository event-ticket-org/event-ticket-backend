package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.storage.ObjectStore;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criterion 19, and criterion 21's other half: an Event with no cover is an
 * ordinary Event.
 *
 * <p>Removing one nobody set is not an error. The caller wanted no cover and there is no cover,
 * which is the outcome they asked for - answering 409 because it was already true would make
 * the endpoint harder to use for no gain.
 */
@Component
public class RemoveEventCover {

    private final EventRepository events;
    private final Managers managers;
    private final ObjectStore store;

    public RemoveEventCover(EventRepository events, Managers managers, ObjectStore store) {
        this.events = events;
        this.managers = managers;
        this.store = store;
    }

    @Transactional
    public void remove(UUID eventId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        event.requireCoverIsChangeable();

        Optional.ofNullable(event.clearCover()).ifPresent(store::delete);
        events.save(event);
    }
}
