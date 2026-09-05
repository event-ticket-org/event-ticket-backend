package com.eventticket.venue.usecase;

import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.Venue;
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
    private final Managers managers;

    public CreateVenue(VenueRepository venues, Managers managers) {
        this.venues = venues;
        this.managers = managers;
    }

    @Transactional
    public Venue create(String name, String address, String city, String timezone) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Venue venue = venues.save(new Venue(organizationId, name, address, city, timezone));
        log.info("Created venue venueId={} city={}", venue.id(), city);
        return venue;
    }
}
