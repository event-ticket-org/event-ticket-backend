package com.eventticket.platform.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.FeaturedSlot;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.FeaturedSlotRepository;
import com.eventticket.platform.support.PlatformAdmins;
import com.eventticket.shared.audit.AuditTrail;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.shared.tenancy.TenantPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the curated row (KB requirements/009 criterion 14).
 *
 * <p><strong>The whole row at once, rather than a slot at a time.</strong> Position is
 * meaningful and relative, so moving one Event up moves another down. Expressed as a series of
 * single-slot edits, a half-applied reorder is a reachable state with no name - two slots at
 * position 2 and nothing at 3 - and every client would have to sequence its own writes to
 * avoid producing one.
 *
 * <p><strong>A placement is refused unless the Event is one the listing would show.</strong>
 * Not because a draft cannot be pointed at, but because the row is part of the public listing:
 * featuring an Event nobody can open is a link to a 404 on the first screen of the site. The
 * check is the same predicate the listing applies, so the two can never disagree about what
 * eligible means.
 */
@Component
public class ReplaceFeaturedSlots {

    private static final Logger log = LoggerFactory.getLogger(ReplaceFeaturedSlots.class);

    private final FeaturedSlotRepository slots;
    private final EventRepository events;
    private final PlatformAdmins admins;
    private final TenantPublisher tenant;
    private final AuditTrail audit;

    public ReplaceFeaturedSlots(FeaturedSlotRepository slots, EventRepository events,
                         PlatformAdmins admins, TenantPublisher tenant, AuditTrail audit) {
        this.slots = slots;
        this.events = events;
        this.admins = admins;
        this.tenant = tenant;
        this.audit = audit;
    }

    /** @param placements in the order they should appear; position is assigned from it. */
    @Transactional
    public List<FeaturedSlot> replace(List<Placement> placements) {
        admins.requireCallerIsPlatformAdmin();
        UUID adminUserId = TenantContext.requireUserId();

        placements.forEach(ReplaceFeaturedSlots::requireOrderedWindow);
        Map<UUID, Event> eligible = requireAllListable(placements);

        slots.deleteAllInBatch();

        List<FeaturedSlot> replaced = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            replaced.add(new FeaturedSlot(placement.eventId(), index + 1,
                    placement.startsAt(), placement.endsAt(), adminUserId));
        }
        List<FeaturedSlot> saved = slots.saveAll(replaced);

        recordAgainstEachOrganization(adminUserId, placements, eligible);
        log.info("Replaced featured slots count={} by userId={}", saved.size(), adminUserId);
        return saved;
    }

    /**
     * One audit entry per Organization whose Event was placed, against that Organization.
     *
     * <p>{@code audit_entry} is tenant-scoped and its {@code organization_id} is not nullable,
     * so there is no such thing as a platform-level entry to write. Recording it against the
     * Organization is not a workaround for that: being featured is a thing that happened to
     * them, and theirs is the trail where somebody would look for it.
     *
     * <p>The tenant is adopted per Organization, which is the documented exception for a use
     * case acting on an Organization from outside it - the same move {@code DecideOrganization}
     * makes, once, and this makes once per Organization in the row. The settings are
     * transaction-local, so each adoption replaces the last and the transaction ends with
     * whichever came last; nothing after this point reads a tenant.
     *
     * <p><strong>What this does not record is the row that was replaced.</strong> A replace
     * deletes the previous slots, so the table says who placed what is showing and when, and
     * nothing says what was showing yesterday. That is a real gap rather than an oversight: a
     * history would mean not deleting, and the shape that needs is a decision for whoever first
     * wants to answer "what did the front page look like last Tuesday".
     */
    private void recordAgainstEachOrganization(UUID adminUserId, List<Placement> placements,
                                               Map<UUID, Event> eligible) {
        Set<UUID> organizations = new LinkedHashSet<>();
        for (Placement placement : placements) {
            Event event = eligible.get(placement.eventId());
            if (event != null) {
                organizations.add(event.organizationId());
            }
        }
        for (UUID organizationId : organizations) {
            tenant.adopt(adminUserId, organizationId);
            audit.record(organizationId, AuditTrail.EVENT_FEATURED, subjectFor(organizationId,
                    placements, eligible));
        }
    }

    private static String subjectFor(UUID organizationId, List<Placement> placements,
                                     Map<UUID, Event> eligible) {
        return placements.stream()
                .map(placement -> eligible.get(placement.eventId()))
                .filter(event -> event != null && event.organizationId().equals(organizationId))
                .map(Event::title)
                .collect(Collectors.joining(", "));
    }

    /**
     * Every placed Event is one the public listing would show, or none of them are placed.
     *
     * <p>All-or-nothing, and named: a partial apply would leave an administrator looking at a
     * row they did not build, with no way to tell which of their choices was dropped.
     */
    private Map<UUID, Event> requireAllListable(List<Placement> placements) {
        List<UUID> ids = placements.stream().map(Placement::eventId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Event> eligible = events
                .findPublicByIds(ids, Instant.now(), Event.Status.PUBLISHED,
                        com.eventticket.organization.domain.Organization.Status.APPROVED)
                .stream().collect(Collectors.toMap(Event::id, Function.identity()));

        List<UUID> refused = ids.stream().filter(id -> !eligible.containsKey(id)).toList();
        if (!refused.isEmpty()) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "Only events the public listing shows can be featured - published, listed, "
                            + "still to come, and from an approved organization. "
                            + refused.size() + " of these are not.",
                    Map.of("eventIds", refused.stream().map(UUID::toString).toList()));
        }
        return eligible;
    }

    private static void requireOrderedWindow(Placement placement) {
        if (!placement.endsAt().isAfter(placement.startsAt())) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "A featured slot must end after it starts.", Map.of("field", "endsAt"));
        }
    }

    /** One placement, as the administrator sent it. Position comes from the order, not from here. */
    public record Placement(UUID eventId, Instant startsAt, Instant endsAt) {}
}
