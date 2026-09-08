package com.eventticket.event.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.venue.domain.MapElement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An occasion with tickets on sale. Publishing is the moment the system's promises begin:
 * from then on, nothing a sold Ticket depends on may change under it (KB invariant 12).
 *
 * <p>What may change and what may not is decided here rather than in the use cases, because
 * the answer is a property of the Event's status and not of the endpoint that asked. The
 * database enforces the same rules again in {@code V4__venues_and_events.sql} - these methods
 * are how a caller gets a civil refusal instead of a constraint violation.
 */
@Entity
@Table(name = "event")
public class Event {

    public enum Status { DRAFT, PUBLISHED, SALES_CLOSED, COMPLETED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "cover_image_key")
    private String coverImageKey;

    @Column(name = "cover_image_alt")
    private String coverImageAlt;

    /**
     * The smaller copies that exist (requirements/003 criterion 22). Null and empty both mean
     * "none", which is an ordinary state: a small upload has nothing smaller worth making, and
     * a format nothing decodes has none at all.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cover_image_renderings")
    private List<CoverRendering> coverImageRenderings;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /**
     * The admission window (requirements/003 criterion 16). Null while the Event is a Draft;
     * publishing requires both.
     *
     * <p>They are not a nicety. A start time cannot decide whether a door is open - people
     * arrive before an event begins and leave after it ends - so without these the scan
     * outcomes EVENT_NOT_OPEN and EVENT_ENDED have nothing to measure against.
     */
    @Column(name = "doors_open_at")
    private Instant doorsOpenAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private boolean listed = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * The cancellation lives on the Event because there is at most one of them and it is a
     * fact about the Event. The per-Order progress requirements/008 criterion 7 asks for is
     * the refund rows, which are the record of the work rather than a second copy of it.
     */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    /**
     * Copied from the Venue at publish, alongside the seats. Stays a document because nothing
     * is ever ticketed against a stage or an aisle.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "map_elements", nullable = false)
    private List<MapElement> mapElements = List.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Event() {}

    public Event(UUID organizationId, UUID venueId, String title, String description,
                 Instant startsAt, Instant doorsOpenAt, Instant endsAt, boolean listed) {
        this.organizationId = organizationId;
        this.venueId = venueId;
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.doorsOpenAt = doorsOpenAt;
        this.endsAt = endsAt;
        this.listed = listed;
        requireWindowOrdered();
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID venueId() {
        return venueId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String coverImageUrl() {
        return coverImageUrl;
    }

    /** Where the object is, which is what removing it needs. Never leaves this module. */
    public String coverImageKey() {
        return coverImageKey;
    }

    public String coverImageAlt() {
        return coverImageAlt;
    }

    /** Smallest first, so a client reading it in order is reading it in the order it will pick. */
    public List<CoverRendering> coverImageRenderings() {
        return coverImageRenderings == null ? List.of()
                : coverImageRenderings.stream()
                        .sorted(Comparator.comparingInt(CoverRendering::width)).toList();
    }

    public boolean hasCover() {
        return coverImageKey != null;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant doorsOpenAt() {
        return doorsOpenAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    /** requirements/007 criterion 4. Before this, nobody is getting in yet. */
    public boolean doorsAreOpen(Instant at) {
        return doorsOpenAt != null && !at.isBefore(doorsOpenAt);
    }

    /** ...and after this, nobody is getting in at all. */
    public boolean hasEnded(Instant at) {
        return endsAt != null && at.isAfter(endsAt);
    }

    public boolean isListed() {
        return listed;
    }

    public Status status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public List<MapElement> mapElements() {
        return mapElements;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * The Event policy admits every published Event, because requirements/003 criterion 13
     * makes one public. A manager's own endpoints want the narrower answer, so someone else's
     * Event is not found here even when the whole world can read its public page.
     */
    public Event requireBelongsTo(UUID organizationId) {
        if (!this.organizationId.equals(organizationId)) {
            throw ApiException.notFound("Event");
        }
        return this;
    }

    /**
     * Refuses before a five-megabyte upload rather than after it.
     *
     * <p>The same condition {@code coverIs} and {@code clearCover} enforce anyway. Asking it
     * early is the difference between "you cannot change a cancelled event" and the same
     * sentence arriving once somebody has waited for a file to cross a phone connection.
     */
    public void requireCoverIsChangeable() {
        requireStillEditable();
    }

    /** requirements/003 criterion 8. */
    public void describeAs(String title, String description) {
        requireStillEditable();
        this.title = title;
        this.description = description;
    }

    /**
     * Alt text without touching the picture, so fixing a description does not mean uploading
     * again (requirements/003 criterion 20). Refused when there is nothing to describe: alt
     * text for an absent image is a sentence about nothing, and the database says so too.
     */
    public void describeCoverAs(String alt) {
        requireStillEditable();
        if (!hasCover()) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "There is no cover image to describe. Upload one first.");
        }
        this.coverImageAlt = blankToNull(alt);
    }

    /**
     * Point this Event at a stored image (requirements/003 criterion 19).
     *
     * <p>Key and URL together, always, because the URL is derived from the key and the moment
     * they disagree the delete removes the wrong object. `event_cover_is_whole` in V9 refuses
     * any other state; this method is why nothing has to try.
     *
     * <p>Answers the key it replaced, or null. The caller deletes it - a store holding every
     * cover an Event ever had is a store nobody can reason about - and doing it here would put
     * an object store inside an entity.
     */
    /**
     * Answers every key that is no longer anybody's cover - the one it replaced and all of that
     * one's renderings - so the caller can delete them. An Event has one cover, and a store
     * full of the ones it used to have is a store nobody can reason about (ADR-0006).
     */
    public List<String> coverIs(String key, String url, String alt,
                                List<CoverRendering> renderings) {
        requireStillEditable();
        List<String> orphaned = coverKeys();
        this.coverImageKey = key;
        this.coverImageUrl = url;
        this.coverImageAlt = blankToNull(alt);
        this.coverImageRenderings = renderings == null || renderings.isEmpty() ? null : renderings;
        return orphaned;
    }

    /** Answers every key that is no longer anybody's cover, empty when there was none. */
    public List<String> clearCover() {
        requireStillEditable();
        List<String> orphaned = coverKeys();
        this.coverImageKey = null;
        this.coverImageUrl = null;
        this.coverImageAlt = null;
        this.coverImageRenderings = null;
        return orphaned;
    }

    /**
     * The cover and everything derived from it, which is what deleting one has to reach.
     *
     * <p>One method rather than two callers each remembering the renderings exist. Forgetting
     * them does not fail anything: it leaves files in the bucket that nothing points at, which
     * is invisible until somebody looks at a bill.
     */
    private List<String> coverKeys() {
        if (coverImageKey == null) {
            return List.of();
        }
        return Stream.concat(Stream.of(coverImageKey),
                coverImageRenderings().stream().map(CoverRendering::key)).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * requirements/003 criteria 9 and 16. Permitted after publish, and the reason the caller is
     * told how many people the change will email: moving an event is not a typo fix.
     *
     * <p>All three instants move together, and are checked once afterwards. Setting them one at
     * a time would refuse every honest reschedule - a start time pushed to next week is past an
     * end time that has not been moved yet, and the intermediate state is not one anybody asked
     * for.
     */
    public void reschedule(Instant startsAt, Instant doorsOpenAt, Instant endsAt) {
        requireStillEditable();
        this.startsAt = startsAt;
        this.doorsOpenAt = doorsOpenAt;
        this.endsAt = endsAt;
        requireWindowOrdered();
        requireStillAhead();
    }

    /**
     * requirements/003 criterion 9: the new time is still in the future.
     *
     * <p>Only once published, because that is when the rule starts costing anybody anything. A
     * Draft may sit at any date its author likes - publishing refuses a past one and says so,
     * which is the gate criterion 5 describes and a better place to be stopped than while
     * typing.
     *
     * <p>Once tickets exist it is different, and this was accepted until it was probed: moving a
     * published Event backwards emails everyone holding a ticket a date that has already been
     * and gone (criterion 9 notifies them all), and leaves a door that will not open because
     * its admission window closed before the message arrived. An Event that has already
     * happened is COMPLETED, which is a status rather than an edit.
     */
    private void requireStillAhead() {
        if (status != Status.PUBLISHED && status != Status.SALES_CLOSED) {
            return;
        }
        if (!startsAt.isAfter(Instant.now())) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "A published event cannot be moved into the past. Everyone holding a ticket "
                            + "would be emailed a date that has already passed.");
        }
    }

    /**
     * A publish precondition, checked with the other four so that a manager is told which one
     * failed rather than that publishing failed.
     */
    public void requireAdmissionWindow() {
        if (doorsOpenAt == null || endsAt == null) {
            throw new ApiException(ErrorCodes.PUBLISH_PRECONDITION_FAILED,
                    "Set when doors open and when the event ends before publishing. "
                            + "The door needs them to tell someone who is early from someone "
                            + "who is late.");
        }
    }

    /** requirements/003 criterion 14. Unlisting hides an Event from the public listing only. */
    public void listPublicly(boolean listed) {
        requireStillEditable();
        this.listed = listed;
    }

    /**
     * requirements/003 criteria 5-7. The preconditions are checked by {@code PublishEvent},
     * which can see the Seat Map and the prices; what belongs here is that publishing happens
     * once and moves the Event out of Draft for good.
     */
    public void publish(Instant at) {
        requireDraft();
        this.status = Status.PUBLISHED;
        this.publishedAt = at;
    }

    /** Checked before the other preconditions: an event on sale is not one to re-publish. */
    public void requireDraft() {
        if (!isDraft()) {
            throw new ApiException(ErrorCodes.PUBLISH_PRECONDITION_FAILED,
                    "This event is " + status.name().toLowerCase().replace('_', ' ')
                            + " and can only be published from draft.");
        }
    }

    public void freezeSeatMapElements(List<MapElement> elements) {
        this.mapElements = List.copyOf(elements);
    }

    /** requirements/003 criterion 12. Tickets already sold stay valid and scannable. */
    /**
     * requirements/008 criterion 6, and KB invariant 22. Cancelling is not closing sales: sales
     * closing stops new Orders and leaves every Ticket good, cancelling voids all of them and
     * gives the money back.
     *
     * <p>A Draft is refused rather than quietly cancelled. It has sold nothing and admits
     * nobody, so there is nothing for a cancellation to undo, and an organizer who reached for
     * this wanted to delete it.
     */
    public void cancel(String reason, Instant at) {
        if (!isPublished()) {
            throw new ApiException(ErrorCodes.EVENT_NOT_CANCELLABLE,
                    "This event was never published, so there is nothing to cancel. Delete it instead.");
        }
        if (status == Status.CANCELLED) {
            throw new ApiException(ErrorCodes.EVENT_NOT_CANCELLABLE,
                    "This event has already been cancelled.");
        }
        this.status = Status.CANCELLED;
        this.cancelledAt = at;
        this.cancelReason = reason;
    }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public void closeSales() {
        if (status != Status.PUBLISHED) {
            throw new ApiException(ErrorCodes.EVENT_FIELD_FROZEN,
                    "Only an event that is on sale can have its sales closed.");
        }
        this.status = Status.SALES_CLOSED;
    }

    /**
     * A cancelled or completed Event is history. Everything else - including a published one -
     * may still have its title, description, images, listing and start time changed
     * (requirements/003 criteria 8 and 9).
     */
    /**
     * requirements/003 criterion 16, which states the two bounds separately: {@code doorsOpenAt}
     * no later than the start, {@code endsAt} after it.
     *
     * <p>Checked whenever any of the three instants moves, not only when the window is set:
     * moving a published Event's start time past its own end is the easy way to make a door
     * refuse everybody.
     *
     * <p><strong>Each bound is checked on its own, because either may be absent.</strong> This
     * used to return early when <em>either</em> was null, which read as "no window, nothing to
     * order" and was not: an Event with doors and no end time had no ordering enforced at all,
     * so doors could be set hours after the start and the API accepted it. The same mistake on
     * an Event that happened to have an end time was refused, which is what kept it hidden -
     * the rule looked like it worked, and only one of the two shapes ever reached it.
     */
    private void requireWindowOrdered() {
        if (doorsOpenAt != null && doorsOpenAt.isAfter(startsAt)) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "Doors must open no later than the event starts.",
                    Map.of("field", "doorsOpenAt"));
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "An event must end after it starts.",
                    Map.of("field", "endsAt"));
        }
    }

    private void requireStillEditable() {
        if (status == Status.CANCELLED || status == Status.COMPLETED) {
            throw new ApiException(ErrorCodes.EVENT_FIELD_FROZEN,
                    "A " + status.name().toLowerCase() + " event can no longer be changed.");
        }
    }
}
