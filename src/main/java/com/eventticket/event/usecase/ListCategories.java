package com.eventticket.event.usecase;

import com.eventticket.event.domain.EventCategory;
import com.eventticket.event.repository.EventCategoryRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The Category set, in display order, catch-all included (KB requirements/009 criterion 12). */
@Component
public class ListCategories {

    private final EventCategoryRepository categories;

    public ListCategories(EventCategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<EventCategory> list() {
        return categories.findAllByOrderByPositionAsc();
    }
}
