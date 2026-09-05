package com.eventticket.venue.usecase;

import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/002 criteria 3-9, all of them. Generating a block of rows, moving a seat,
 * relabelling one, adding a stage: the editor works out what the map should look like and
 * sends the result, and this replaces it.
 *
 * <p>That is the whole reason the map is a document. There is no add-seat, move-seat or
 * delete-seat endpoint to keep consistent with each other, and a 2,000-seat edit is one
 * statement.
 */
@Component
public class ReplaceSeatMap {

    private static final Logger log = LoggerFactory.getLogger(ReplaceSeatMap.class);

    private final VenueRepository venues;
    private final Managers managers;

    public ReplaceSeatMap(VenueRepository venues, Managers managers) {
        this.venues = venues;
        this.managers = managers;
    }

    @Transactional
    public SeatMapDocument replace(UUID venueId, SeatMapDocument replacement) {
        managers.requireCallerCanManageEvents(TenantContext.requireOrganizationId());

        Venue venue = venues.findOrThrow(venueId).requireBelongsTo(TenantContext.requireOrganizationId());
        venue.redraw(replacement);
        venues.save(venue);

        log.info("Replaced seat map venueId={} seats={} elements={}",
                venueId, replacement.seats().size(), replacement.elements().size());
        return venue.seatMap();
    }
}
