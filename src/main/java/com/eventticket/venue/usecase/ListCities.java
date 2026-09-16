package com.eventticket.venue.usecase;

import com.eventticket.venue.domain.City;
import com.eventticket.venue.repository.CityRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cities a Venue may be in, in display order (KB requirements/009 criterion 13).
 *
 * <p>A use case for a read of a ten-row reference table looks like ceremony, and is the
 * convention holding: controllers call use cases and never repositories (ADR-0001). The
 * transaction boundary is the part that is not ceremony - without it this read runs outside
 * {@code TenantAwareTransactionManager} and therefore as the connection user, which in
 * development and test is a superuser.
 */
@Component
public class ListCities {

    private final CityRepository cities;

    public ListCities(CityRepository cities) {
        this.cities = cities;
    }

    @Transactional(readOnly = true)
    public List<City> list() {
        return cities.findAllByOrderByPositionAsc();
    }
}
