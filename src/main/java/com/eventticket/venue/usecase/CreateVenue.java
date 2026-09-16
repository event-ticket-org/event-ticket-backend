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

/** requirements/002 criteria 1 and 2. The Seat Map is created with it, and starts empty. */
@Component
public class CreateVenue {

    private static final Logger log = LoggerFactory.getLogger(CreateVenue.class);

    private final VenueRepository venues;
    private final CityRepository cities;
    private final Managers managers;

    public CreateVenue(VenueRepository venues, CityRepository cities, Managers managers) {
        this.venues = venues;
        this.cities = cities;
        this.managers = managers;
    }

    @Transactional
    public VenueView create(String name, String address, String citySlug, String timezone) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        // Resolved before the write, so an unknown slug is refused by name rather than by a
        // foreign key violation - and the row it returns is the name the response prints.
        City city = cities.findOrThrow(citySlug);
        Venue venue = venues.save(new Venue(organizationId, name, address, city.slug(), timezone));
        log.info("Created venue venueId={} city={}", venue.id(), citySlug);
        return new VenueView(venue, city.name());
    }
}
