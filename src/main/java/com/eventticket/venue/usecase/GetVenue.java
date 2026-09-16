package com.eventticket.venue.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.domain.VenueView;
import com.eventticket.venue.repository.CityRepository;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GetVenue {

    private final VenueRepository venues;
    private final CityRepository cities;

    public GetVenue(VenueRepository venues, CityRepository cities) {
        this.venues = venues;
        this.cities = cities;
    }

    @Transactional(readOnly = true)
    public VenueView get(UUID venueId) {
        Venue venue = venues.findOrThrow(venueId)
                .requireBelongsTo(TenantContext.requireOrganizationId());
        return new VenueView(venue, cities.findOrThrow(venue.citySlug()).name());
    }
}
