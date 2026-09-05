package com.eventticket.venue.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A read is a use case too, so that no controller ever reaches a repository (ADR-0001). */
@Component
public class ListVenues {

    private final VenueRepository venues;

    public ListVenues(VenueRepository venues) {
        this.venues = venues;
    }

    @Transactional(readOnly = true)
    public List<Venue> list() {
        return venues.findByOrganizationIdOrderByNameAsc(TenantContext.requireOrganizationId());
    }
}
