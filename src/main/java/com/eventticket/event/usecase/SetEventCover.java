package com.eventticket.event.usecase;

import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventDetail;
import com.eventticket.event.domain.CoverRendering;
import com.eventticket.event.domain.EventPricing;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.organization.domain.Managers;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.storage.ImageRenderer;
import com.eventticket.shared.storage.ImageType;
import com.eventticket.shared.storage.ObjectStore;
import com.eventticket.shared.storage.StoredObject;
import com.eventticket.shared.tenancy.TenantContext;
import com.eventticket.venue.repository.VenueRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/003 criteria 18 and 19: looks at what arrived, and adopts it or throws it away.
 *
 * <p>This is the first moment the system has seen the file. The upload went straight to the
 * store, which is the point of the design and also its cost - so the check that a
 * through-the-application upload would have done at the door happens here instead, and happens
 * on the bytes rather than on anything a client said about them.
 *
 * <p>A file that is not an image is deleted, not left sitting in the bucket. Refusing it and
 * keeping it would leave the store holding whatever somebody tried to upload, addressed by a
 * key they know.
 */
@Component
public class SetEventCover {

    private static final Logger log = LoggerFactory.getLogger(SetEventCover.class);

    private final EventRepository events;
    private final PricingTierRepository tiers;
    private final VenueRepository venues;
    private final Managers managers;
    private final ObjectStore store;
    private final ImageRenderer renderer;

    /**
     * The widths a cover is offered at.
     *
     * <p>320 is the listing's band on a phone at twice the pixel density, 640 is the public
     * shell's whole column, and 1280 is that column on a retina screen - so the largest is the
     * largest anything here can draw. Which of them exist for a given Event is recorded rather
     * than assumed, because only sizes smaller than the upload are made.
     */
    private static final int[] WIDTHS = {320, 640, 1280};

    public SetEventCover(EventRepository events, PricingTierRepository tiers,
                         VenueRepository venues, Managers managers, ObjectStore store,
                         ImageRenderer renderer) {
        this.events = events;
        this.tiers = tiers;
        this.venues = venues;
        this.managers = managers;
        this.store = store;
        this.renderer = renderer;
    }

    @Transactional
    public EventDetail set(UUID eventId, String uploadId, String alt) {
        UUID organizationId = TenantContext.requireOrganizationId();
        managers.requireCallerCanManageEvents(organizationId);

        Event event = events.findOrThrow(eventId).requireBelongsTo(organizationId);
        event.requireCoverIsChangeable();

        if (!CoverImageKeys.isWellFormed(uploadId)) {
            throw new ApiException(ErrorCodes.COVER_NOT_UPLOADED,
                    "That upload does not exist. Start again.");
        }
        String pendingKey = CoverImageKeys.pending(organizationId, eventId, uploadId);

        StoredObject uploaded = store.describe(pendingKey).orElseThrow(() -> new ApiException(
                ErrorCodes.COVER_NOT_UPLOADED,
                "Nothing was uploaded, or the upload has expired. Choose the image again."));

        ImageType type = ImageType.of(store.readLeadingBytes(pendingKey, ImageType.LEADING_BYTES))
                .orElseThrow(() -> {
                    // Deleted before the exception rolls anything back: the object is not part
                    // of the transaction, and leaving it would mean keeping a file we have just
                    // decided we will not serve.
                    store.delete(pendingKey);
                    log.info("Refused a cover that is not an image eventId={} size={}",
                            eventId, uploaded.size());
                    return new ApiException(ErrorCodes.COVER_NOT_AN_IMAGE,
                            "That file is not an image we can show. JPEG, PNG, WebP and AVIF work.");
                });

        String servedKey = CoverImageKeys.served(organizationId, eventId, uploadId, type.extension());
        store.promote(pendingKey, servedKey, type.contentType());

        List<CoverRendering> renderings = render(organizationId, eventId, uploadId, servedKey);

        // Everything the previous cover owned - the file and its renderings. An Event has one
        // cover, and a store full of the ones it used to have is a store nobody can reason
        // about (ADR-0006).
        event.coverIs(servedKey, store.publicUrl(servedKey), alt, renderings)
                .forEach(store::delete);
        events.save(event);

        log.info("Set cover eventId={} type={} bytes={} renderings={}",
                eventId, type, uploaded.size(), renderings.size());
        return detailOf(event, eventId);
    }

    /**
     * The smaller copies, written beside the cover (requirements/003 criterion 22).
     *
     * <p>After the promote rather than before it: the renderer needs the whole file, and
     * reading it from where it now lives keeps the pending prefix's only job the one it has.
     *
     * <p>Best effort throughout. An empty answer is ordinary - a small upload has nothing
     * smaller, and an AVIF has no decoder (ADR-0006) - and it is not worth failing somebody's
     * upload over an optimisation, so an Event with no renderings is served whole.
     */
    private List<CoverRendering> render(UUID organizationId, UUID eventId, String uploadId,
                                        String servedKey) {
        var written = new ArrayList<CoverRendering>();
        for (var rendering : renderer.renderingsOf(store.read(servedKey), WIDTHS)) {
            String key = CoverImageKeys.rendering(organizationId, eventId, uploadId,
                    rendering.width(), rendering.type().extension());
            store.put(key, rendering.content(), rendering.type().contentType());
            written.add(new CoverRendering(rendering.width(), key, store.publicUrl(key)));
        }
        return written;
    }

    private EventDetail detailOf(Event event, UUID eventId) {
        var map = event.isPublished() ? null : venues.findOrThrow(event.venueId()).seatMap();
        EventDetail detail = EventDetail.of(event,
                EventPricing.of(event, map, tiers.findByEventId(eventId)));
        return events.countsFor(java.util.List.of(eventId)).stream().findFirst()
                .map(counts -> detail.withCounts(counts.getSold(), counts.getRefundRequired(), counts.salesTotal()))
                .orElse(detail);
    }
}
