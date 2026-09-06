package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.storage.ObjectStore;
import com.eventticket.shared.storage.UploadForm;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criterion 17: authorises one image to be sent straight to the store.
 *
 * <p>Nothing is written here - not a row, not an object. An upload that is begun and abandoned
 * leaves an unreferenced object under the pending prefix and a lifecycle rule removes it, which
 * is cheaper than a table of intentions somebody has to reconcile.
 *
 * <p>The Event is loaded to answer two questions before signing anything: does it belong to the
 * caller's Organization, and is it still editable. A cancelled Event refusing an upload here is
 * better than accepting the file and refusing it at confirmation, after somebody has waited for
 * five megabytes to cross a phone connection.
 */
@Component
public class BeginCoverUpload {

    private final EventRepository events;
    private final Managers managers;
    private final ObjectStore store;

    public BeginCoverUpload(EventRepository events, Managers managers, ObjectStore store) {
        this.events = events;
        this.managers = managers;
        this.store = store;
    }

    public record Authorised(String uploadId, UploadForm form) {}

    @Transactional(readOnly = true)
    public Authorised begin(UUID eventId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        event.requireCoverIsChangeable();

        String uploadId = UUID.randomUUID().toString();
        String key = CoverImageKeys.pending(organizationId, eventId, uploadId);
        return new Authorised(uploadId, store.presignUpload(key));
    }
}
