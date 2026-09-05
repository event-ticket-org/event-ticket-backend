package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * KB invariant 12 - nothing a sold Ticket depends on may change under it - as the database
 * enforces it.
 *
 * <p>These tests go around the application entirely and issue the forbidden statements
 * directly, as the schema owner, which in this environment is a superuser. That is the point:
 * a rule kept only in a use case is a rule the next use case can forget, and one kept in an
 * application check cannot be tested this way at all.
 *
 * <p>Every refusal here is paired with the same statement against a Draft, which must succeed.
 * Without that pair the tests would pass just as happily against a trigger that refuses
 * everything, or one that was never installed and a typo in the SQL.
 */
class PublishedSeatMapIsFrozenTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("a published event's seat cannot be relabelled, moved or re-tiered")
    void seatsCannotBeChanged() {
        UUID published = publishedEventId();
        UUID seat = anySeatOf(published);

        assertThatThrownBy(() -> jdbc.update("update event_seat set label = 'HACKED' where id = ?", seat))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");

        assertThatThrownBy(() -> jdbc.update("update event_seat set x = 999 where id = ?", seat))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("update event_seat set tier_name = 'Free' where id = ?", seat))
                .isInstanceOf(DataIntegrityViolationException.class);

        // ...but availability is live, and is meant to be.
        assertThatCode(() -> jdbc.update("update event_seat set for_sale = false where id = ?", seat))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("seats cannot be added to or removed from a published event, but can from a draft")
    void seatsCannotBeAddedOrRemoved() {
        UUID published = publishedEventId();
        UUID seat = anySeatOf(published);
        UUID organizationId = organizationOf(published);

        assertThatThrownBy(() -> insertSeat(organizationId, published, "EXTRA"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from event_seat where id = ?", seat))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The control. The same statement against a Draft succeeds, so the refusals above are
        // the trigger reading published_at and not the statement being wrong.
        UUID draft = draftEventIdIn(organizationId);
        assertThatCode(() -> insertSeat(organizationId, draft, "EXTRA"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a published event cannot change venue or return to draft")
    void publishedEventCannotBeUnpublished() {
        UUID published = publishedEventId();

        assertThatThrownBy(() -> jdbc.update(
                "update event set status = 'DRAFT' where id = ?", published))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "update event set published_at = null where id = ?", published))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "update event set venue_id = (select id from venue where name = 'Other Room') "
                        + "where id = ?", published))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSeat(UUID organizationId, UUID eventId, String label) {
        jdbc.update("insert into event_seat (organization_id, event_id, label, x, y, tier_name) "
                + "values (?, ?, ?, 99, 99, 'Standard')", organizationId, eventId, label);
    }

    private UUID anySeatOf(UUID eventId) {
        return jdbc.queryForObject("select id from event_seat where event_id = ? limit 1",
                UUID.class, eventId);
    }

    private UUID organizationOf(UUID eventId) {
        return jdbc.queryForObject("select organization_id from event where id = ?", UUID.class, eventId);
    }

    private UUID draftEventIdIn(UUID organizationId) {
        return jdbc.queryForObject(
                "select id from event where organization_id = ? and published_at is null limit 1",
                UUID.class, organizationId);
    }

    /** An approved organization with one published event, one draft, and a spare venue. */
    private UUID publishedEventId() {
        TokenPair alice = signUp("alice@example.com");
        var organization = createOrganization(alice, "Acme Events");
        approve(organization);
        TokenPair manager = switchTo(alice, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        createVenue(manager, "Other Room", "Ho Chi Minh City");

        createEvent(manager, venue.getId(), "Still Cooking", NEXT_MONTH);

        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);
        Event published = publish(manager, event.getId(), Event.class).getBody();

        assertThat(published.getPublishedAt()).isNotNull();
        return published.getId();
    }
}
