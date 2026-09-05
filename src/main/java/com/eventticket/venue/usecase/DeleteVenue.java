package com.eventticket.venue.usecase;

import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/002 criterion 11.
 *
 * <p>The refusal is a trigger in {@code V4__venues_and_events.sql}, not an {@code if} here,
 * and this module is the reason why: asking "does a published Event use this Venue?" in Java
 * would make {@code venue} depend on {@code event}, which already depends on {@code venue} to
 * read the map at publish. The database can answer it without either module knowing about the
 * other, so it does.
 *
 * <p>Unpublished Events at this Venue go with it - the foreign key cascades. Deleting a room
 * you never sold tickets for should not require deleting a draft first.
 */
@Component
public class DeleteVenue {

    private static final Logger log = LoggerFactory.getLogger(DeleteVenue.class);

    private final VenueRepository venues;
    private final Managers managers;

    public DeleteVenue(VenueRepository venues, Managers managers) {
        this.venues = venues;
        this.managers = managers;
    }

    @Transactional
    public void delete(UUID venueId) {
        managers.requireCallerCanManageEvents(TenantContext.requireOrganizationId());

        Venue venue = venues.findOrThrow(venueId).requireBelongsTo(TenantContext.requireOrganizationId());
        try {
            venues.delete(venue);
            // Without the flush the trigger fires at commit, outside this try, and the client
            // gets a 500 for a refusal the contract has a code for.
            venues.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Venue deletion refused: venueId={} is used by a published event", venueId);
            throw new ApiException(ErrorCodes.VENUE_IN_USE,
                    "This venue is used by a published event and cannot be deleted. "
                            + "Tickets already sold refer to it.");
        }
        log.info("Deleted venue venueId={}", venueId);
    }
}
