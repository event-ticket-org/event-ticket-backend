package com.eventticket.event.repository;

import com.eventticket.event.domain.EventCategory;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Platform vocabulary, readable by everybody including a visitor with no tenant. */
public interface EventCategoryRepository extends JpaRepository<EventCategory, String> {

    public List<EventCategory> findAllByOrderByPositionAsc();

    public default EventCategory findOrThrow(String slug) {
        return findById(slug).orElseThrow(() -> new ApiException(ErrorCodes.VALIDATION_FAILED,
                "There is no category with that name. Choose one from /public/categories."));
    }
}
