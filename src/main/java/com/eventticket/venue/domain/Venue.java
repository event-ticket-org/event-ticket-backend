package com.eventticket.venue.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.eventticket.shared.error.ApiException;
import java.time.Instant;
import java.util.UUID;

/**
 * A room an Organization uses, and the Seat Map drawn for it. The map is the reusable asset:
 * drawn once, used by every Event held there (requirements/002).
 */
@Document(collection = "venue")
public class Venue {

    @Id
    private UUID id = UUID.randomUUID();

    private UUID organizationId;

    private String name;

    private String address;

    /** Separate from the address because the public listing filters on it (requirements/009). */
    private String city;

    /**
     * An IANA zone. nfr.md stores every instant in UTC and displays it in the Venue's zone, so
     * this is the only place the local wall-clock time of an Event can come from.
     */
    private String timezone;

    private SeatMapDocument seatMap = SeatMapDocument.empty();

    private Instant createdAt = Instant.now();

    protected Venue() {}

    public Venue(UUID organizationId, String name, String address, String city, String timezone) {
        this.organizationId = organizationId;
        this.name = name;
        this.address = address;
        this.city = city;
        this.timezone = timezone;
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public String city() {
        return city;
    }

    public String timezone() {
        return timezone;
    }

    public SeatMapDocument seatMap() {
        return seatMap;
    }

    public int seatCount() {
        return seatMap.seats().size();
    }

    /**
     * The Venue policy also admits venues that a published Event has made public, which is
     * what the public Event page reads. A manager's own endpoints want the narrower answer,
     * and someone else's venue is *not found* here rather than merely refused - saying "found
     * but not yours" would confirm it exists.
     */
    public Venue requireBelongsTo(UUID organizationId) {
        if (!this.organizationId.equals(organizationId)) {
            throw ApiException.notFound("Venue");
        }
        return this;
    }

    public void describeAs(String name, String address, String city, String timezone) {
        this.name = name;
        this.address = address;
        this.city = city;
        this.timezone = timezone;
    }

    /**
     * requirements/002 criterion 9: a Seat Map may be edited at any time, and edits never
     * affect an Event that is already published. Nothing here needs to know that, because a
     * published Event no longer reads this map - it took its own copy at publish.
     */
    public void redraw(SeatMapDocument replacement) {
        this.seatMap = replacement.validated();
    }
}
