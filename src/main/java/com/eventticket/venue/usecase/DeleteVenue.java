package com.eventticket.venue.usecase;

import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.domain.Venue;
import com.eventticket.venue.repository.VenueRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/002 criterion 11.
 *
 * <h2>A trigger that had a job no application code can do as well</h2>
 *
 * <p>The refusal used to be the {@code venue_in_use} trigger in
 * {@code V4__venues_and_events.sql}, and the reason was architectural rather than
 * convenient. Asking "does a published Event use this Venue?" in Java makes {@code venue}
 * depend on {@code event} - and {@code event} already depends on {@code venue}, to read the
 * seat map at publish. The database could answer without either module knowing about the
 * other, so the rule this codebase adopted was: <em>when a module needs one fact about a
 * module that already depends on it, ask the database.</em>
 *
 * <p>MongoDB has no triggers. {@code $jsonSchema} validators cannot help either - they see one
 * document, and this question is about a different collection. So the check comes back into
 * application code, and the dependency it was avoiding comes with it.
 *
 * <p><strong>The compromise below is worth understanding, because it is a compromise.</strong>
 * The query goes to the {@code event} collection by name through {@link MongoTemplate}, with no
 * Java import from the {@code event} module - so Spring Modulith still passes and the compiler
 * still sees two independent modules. That is honest about the package graph and dishonest
 * about the truth: this class now knows that a collection called {@code event} exists, that it
 * has a {@code venueId}, and that {@code publishedAt} is how "published" is spelled. Rename any
 * of those three in the {@code event} module and this silently permits a deletion it should
 * refuse. The trigger could not go stale that way, because it lived beside the thing it read.
 *
 * <h2>And it is no longer atomic</h2>
 *
 * <p>The trigger fired inside the delete. This reads first and deletes second, so an Event
 * published in between is a Venue deleted out from under it. The window is small and the
 * consequence is not: KB invariant 12 exists because a sold Ticket must still refer to a seat
 * that means something. A transaction would narrow it, but MongoDB transactions do not lock a
 * document that was merely <em>read</em> - so unlike {@code SELECT … FOR UPDATE}, even that
 * would not close it.
 *
 * <p>Unpublished Events at this Venue used to go with it, by {@code ON DELETE CASCADE}. There
 * are no cascades either; they are deleted explicitly below.
 */
@Component
public class DeleteVenue {

    private static final Logger log = LoggerFactory.getLogger(DeleteVenue.class);

    private final VenueRepository venues;
    private final Managers managers;
    private final MongoTemplate mongo;

    public DeleteVenue(VenueRepository venues, Managers managers, MongoTemplate mongo) {
        this.venues = venues;
        this.managers = managers;
        this.mongo = mongo;
    }

    @Transactional
    public void delete(UUID venueId) {
        managers.requireCallerCanManageEvents(TenantContext.requireOrganizationId());

        Venue venue = venues.findOrThrow(venueId).requireBelongsTo(TenantContext.requireOrganizationId());

        if (mongo.exists(new Query(Criteria.where("venueId").is(venueId)
                .and("publishedAt").ne(null)), "event")) {
            log.warn("Venue deletion refused: venueId={} is used by a published event", venueId);
            throw new ApiException(ErrorCodes.VENUE_IN_USE,
                    "This venue is used by a published event and cannot be deleted. "
                            + "Tickets already sold refer to it.");
        }

        // What ON DELETE CASCADE did. Drafts at this Venue go with it - deleting a room you
        // never sold tickets for should not require deleting a draft first - and now somebody
        // has to remember to write that, and to keep writing it as collections are added.
        mongo.remove(new Query(Criteria.where("venueId").is(venueId)), "event");
        venues.delete(venue);

        log.info("Deleted venue venueId={}", venueId);
    }
}
