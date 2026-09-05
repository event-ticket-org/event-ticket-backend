package com.eventticket.venue.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.SeatMapDocument;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The map a Manager is drawing, and - because a Draft Event has no seats of its own - also
 * the map a Draft Event shows (requirements/003 criterion 2). A draft reflects edits to the
 * room for free, by reading the room.
 */
@Component
public class GetSeatMap {

    private final VenueRepository venues;

    public GetSeatMap(VenueRepository venues) {
        this.venues = venues;
    }

    @Transactional(readOnly = true)
    public SeatMapDocument get(UUID venueId) {
        return venues.findOrThrow(venueId)
                .requireBelongsTo(TenantContext.requireOrganizationId())
                .seatMap();
    }
}
