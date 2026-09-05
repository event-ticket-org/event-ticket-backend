package com.eventticket.venue.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GetVenue {

    private final VenueRepository venues;

    public GetVenue(VenueRepository venues) {
        this.venues = venues;
    }

    @Transactional(readOnly = true)
    public Venue get(UUID venueId) {
        return venues.findOrThrow(venueId).requireBelongsTo(TenantContext.requireOrganizationId());
    }
}
