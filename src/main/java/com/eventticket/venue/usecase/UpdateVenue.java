package com.eventticket.venue.usecase;

import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.City;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.domain.VenueView;
import com.eventticket.venue.repository.CityRepository;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Venue's description may change at any time, including for Events already published: an
 * address correction is not something a sold Ticket depends on. The Seat Map is the part that
 * freezes, and it freezes by being copied rather than by being locked.
 */
@Component
public class UpdateVenue {

    private static final Logger log = LoggerFactory.getLogger(UpdateVenue.class);

    private final VenueRepository venues;
    private final CityRepository cities;
    private final Managers managers;

    public UpdateVenue(VenueRepository venues, CityRepository cities, Managers managers) {
        this.venues = venues;
        this.cities = cities;
        this.managers = managers;
    }

    @Transactional
    public VenueView update(UUID venueId, String name, String address, String citySlug,
                            String timezone) {
        managers.requireCallerCanManageEvents(TenantContext.requireOrganizationId());

        Venue venue = venues.findOrThrow(venueId).requireBelongsTo(TenantContext.requireOrganizationId());
        City city = cities.findOrThrow(citySlug);
        venue.describeAs(name, address, city.slug(), timezone);
        log.info("Updated venue venueId={}", venueId);
        return new VenueView(venues.save(venue), city.name());
    }
}
