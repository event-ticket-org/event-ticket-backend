package com.eventticket.venue.usecase;

import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.City;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.domain.VenueView;
import com.eventticket.venue.repository.CityRepository;
import com.eventticket.venue.repository.VenueRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A read is a use case too, so that no controller ever reaches a repository (ADR-0001). */
@Component
public class ListVenues {

    private final VenueRepository venues;
    private final CityRepository cities;

    public ListVenues(VenueRepository venues, CityRepository cities) {
        this.venues = venues;
        this.cities = cities;
    }

    @Transactional(readOnly = true)
    public List<VenueView> list() {
        List<Venue> found =
                venues.findByOrganizationIdOrderByNameAsc(TenantContext.requireOrganizationId());
        // One query for the whole page rather than one per Venue. The table is small enough
        // that reading all of it beats assembling a predicate, and this is the shape a mapped
        // association would have hidden - and then performed a row at a time.
        java.util.Map<String, String> names = cities.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(City::slug, City::name));
        return found.stream()
                .map(venue -> new VenueView(venue, names.get(venue.citySlug())))
                .toList();
    }
}
