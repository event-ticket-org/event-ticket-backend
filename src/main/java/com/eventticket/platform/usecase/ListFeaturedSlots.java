package com.eventticket.platform.usecase;

import com.eventticket.event.domain.FeaturedSlot;
import com.eventticket.event.repository.FeaturedSlotRepository;
import com.eventticket.platform.support.PlatformAdmins;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole curated row, past, present and scheduled - which is what an administrator has to
 * see to schedule the next one. {@code /public/featured-events} shows only what is live now,
 * and an administrator who could see only that could never plan anything.
 */
@Component
public class ListFeaturedSlots {

    private final FeaturedSlotRepository slots;
    private final PlatformAdmins admins;

    public ListFeaturedSlots(FeaturedSlotRepository slots, PlatformAdmins admins) {
        this.slots = slots;
        this.admins = admins;
    }

    @Transactional(readOnly = true)
    public List<FeaturedSlot> list() {
        admins.requireCallerIsPlatformAdmin();
        return slots.findAllByOrderByStartsAtAscPositionAsc();
    }
}
