package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * KB invariant 12 - nothing a sold Ticket depends on may change under it - <strong>is no longer
 * enforced by the database, and this test exists to prove that rather than to pretend
 * otherwise.</strong>
 *
 * <h2>What this file used to do</h2>
 *
 * <p>It went around the application entirely and issued the forbidden statements directly, as
 * the schema owner. {@code event_seat_frozen} and {@code event_frozen} refused each one, and
 * every refusal was paired with the same statement against a Draft, which had to succeed - so
 * the test could not pass against a trigger that refused everything, or one that was never
 * installed.
 *
 * <p>That was the strongest guarantee in the system. A rule kept only in a use case is a rule
 * the next use case can forget; a rule in a trigger cannot be forgotten by anybody, including
 * a migration script, a support engineer with a {@code psql} session, or a bug.
 *
 * <h2>Why it cannot be kept</h2>
 *
 * <p>MongoDB has no triggers. {@code $jsonSchema} validation is the nearest thing and it is not
 * near: a validator sees the document being written and <strong>cannot see the document's
 * previous value</strong>, so "this field may not change once published" is inexpressible. There
 * is no formulation of it that MongoDB can enforce.
 *
 * <p>So the checks moved into {@code Event.requireStillEditable()} and its neighbours, where
 * they were <em>already</em> duplicated - the application always refused these too, so that a
 * caller got a civil error instead of a constraint violation. What is gone is the second line
 * of defence, and with it the ability to test the rule this way at all.
 *
 * <h2>What these tests assert now</h2>
 *
 * <p>Each one performs a write the database used to refuse and asserts that <strong>it
 * succeeds</strong> - that the seat map of a published Event can be silently corrupted by
 * anything holding a connection. They pass, and they are failures. Read them as the receipt for
 * a guarantee that was traded away, and keep them: if MongoDB ever grows a mechanism for this,
 * these are the tests that will start failing and say so.
 */
class PublishedSeatMapIsFrozenTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("LOST: a published event's seat can now be relabelled, moved and re-tiered")
    void seatsCanBeChangedAndNothingRefuses() {
        UUID published = publishedEventId();
        UUID seat = anySeatOf(published);

        assertThat(setField("eventSeat", Criteria.where("_id").is(seat), "label", "HACKED"))
                .isEqualTo(1L);
        assertThat(setField("eventSeat", Criteria.where("_id").is(seat), "x", 999.0))
                .isEqualTo(1L);
        assertThat(setField("eventSeat", Criteria.where("_id").is(seat), "tierName", "Free"))
                .isEqualTo(1L);

        // The label a sold Ticket refers to is now something else, and nothing objected.
        assertThat(readField("eventSeat", seat, "label", String.class)).isEqualTo("HACKED");
    }

    @Test
    @DisplayName("LOST: seats can now be added to and removed from a published event")
    void seatsCanBeAddedAndRemoved() {
        UUID published = publishedEventId();
        UUID seat = anySeatOf(published);
        long before = countIn("eventSeat", Criteria.where("eventId").is(published));

        mongo.remove(new org.springframework.data.mongodb.core.query.Query(
                Criteria.where("_id").is(seat)), "eventSeat");

        assertThat(countIn("eventSeat", Criteria.where("eventId").is(published)))
                .isEqualTo(before - 1);
    }

    @Test
    @DisplayName("LOST: a published event can now return to draft and change venue")
    void publishedEventCanBeUnpublished() {
        UUID published = publishedEventId();

        assertThat(setField("event", Criteria.where("_id").is(published), "status", "DRAFT"))
                .isEqualTo(1L);
        assertThat(setField("event", Criteria.where("_id").is(published), "publishedAt", null))
                .isEqualTo(1L);

        // An Event that has sold tickets is now a Draft again, which V4 spent a trigger and a
        // named exception making impossible.
        assertThat(readField("event", published, "publishedAt", Object.class)).isNull();
    }

    // No "the application still refuses it" test here, deliberately. The application's own
    // checks are covered where they belong - EventLifecycleTest drives them over HTTP - and a
    // duplicate here would blur what this file is for. This file is about the floor underneath
    // those checks, and the floor is gone.

    private UUID venueId;

    private TokenPair approvedManagerWithVenue() {
        TokenPair alice = signUp("alice+" + UUID.randomUUID() + "@example.com");
        var organization = createOrganization(alice, "Acme Events");
        approve(organization);
        TokenPair manager = switchTo(alice, organization);
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        venueId = venue.getId();
        return manager;
    }

    private UUID anySeatOf(UUID eventId) {
        return readFieldWhere("eventSeat", Criteria.where("eventId").is(eventId), "_id", UUID.class);
    }

    /** An approved organization with one published event, one draft, and a spare venue. */
    private UUID publishedEventId() {
        TokenPair manager = approvedManagerWithVenue();
        createVenue(manager, "Other Room", "Ho Chi Minh City");
        createEvent(manager, venueId, "Still Cooking", NEXT_MONTH);

        Event event = createEvent(manager, venueId, "Live in Saigon", NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);
        Event published = publish(manager, event.getId(), Event.class).getBody();

        assertThat(published.getPublishedAt()).isNotNull();
        return published.getId();
    }
}
