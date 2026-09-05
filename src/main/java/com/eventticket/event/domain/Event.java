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
import java.util.List;
import java.util.UUID;
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

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private boolean listed = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

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
                 String coverImageUrl, Instant startsAt, boolean listed) {
        this.organizationId = organizationId;
        this.venueId = venueId;
        this.title = title;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.startsAt = startsAt;
        this.listed = listed;
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

    public Instant startsAt() {
        return startsAt;
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

    /** requirements/003 criterion 8. */
    public void describeAs(String title, String description, String coverImageUrl) {
        requireStillEditable();
        this.title = title;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
    }

    /**
     * requirements/003 criterion 9. Permitted after publish, and the reason the caller is
     * told how many people the change will email: moving an event is not a typo fix.
     */
    public void moveTo(Instant startsAt) {
        requireStillEditable();
        this.startsAt = startsAt;
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
    private void requireStillEditable() {
        if (status == Status.CANCELLED || status == Status.COMPLETED) {
            throw new ApiException(ErrorCodes.EVENT_FIELD_FROZEN,
                    "A " + status.name().toLowerCase() + " event can no longer be changed.");
        }
    }
}
