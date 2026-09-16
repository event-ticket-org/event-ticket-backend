package com.eventticket.venue.repository;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.venue.domain.City;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No row-level security stands between this and a caller, and none should: every Organization
 * sees the same cities, and so does a visitor with no tenant at all - which is what makes
 * {@code GET /public/cities} answerable.
 */
public interface CityRepository extends JpaRepository<City, String> {

    public List<City> findAllByOrderByPositionAsc();

    /**
     * A slug nobody seeded is the caller's mistake and is named as one. The alternative - a
     * foreign key violation - reaches the caller as "the request could not be completed", which
     * is true of a database error and useless to somebody who mistyped a city.
     */
    public default City findOrThrow(String slug) {
        return findById(slug).orElseThrow(() -> new ApiException(ErrorCodes.VALIDATION_FAILED,
                "There is no city with that name. Choose one from /public/cities."));
    }
}
