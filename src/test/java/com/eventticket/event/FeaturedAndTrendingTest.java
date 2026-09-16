package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.FeaturedSlot;
import com.eventticket.api.model.FeaturedSlotInput;
import com.eventticket.api.model.PublicEventSummary;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.TrendingEvent;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The two rows above the listing: the curated one and the ranked one
 * (requirements/009 criteria 14, 15 and 16).
 *
 * <p>What they have in common is the thing worth testing hardest: both are allowed to answer
 * nothing, and both must answer nothing rather than answer badly. A row of two is not a
 * selection and a chart of two is not a chart, and the alternative to an empty array is a home
 * page with a heading and a gap under it.
 */
class FeaturedAndTrendingTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH =
            OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    // ---- the curated row (criterion 14) ----

    @Test
    @DisplayName("what an administrator places is what the row shows, in the order they placed it")
    void theRowIsWhatWasPlaced() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event first = publish(manager, venue, "Placed First", NEXT_MONTH);
        Event second = publish(manager, venue, "Placed Second", NEXT_MONTH.plusDays(1));
        Event third = publish(manager, venue, "Placed Third", NEXT_MONTH.plusDays(2));

        // Deliberately not in start-time order: the curated row is an ordering somebody chose,
        // and a row that quietly re-sorted by date would be the listing with a heading on it.
        place(third, second, first);

        assertThat(titles(featured())).containsExactly("Placed Third", "Placed Second", "Placed First");
    }

    @Test
    @DisplayName("a row too short to look like a selection is not shown at all")
    void aShortRowIsNotShown() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event one = publish(manager, venue, "Alone Up There", NEXT_MONTH);
        Event two = publish(manager, venue, "Almost Alone", NEXT_MONTH.plusDays(1));

        place(one, two);

        assertThat(featured()).isEmpty();
        // ...and the administrator can still see both, or they could never remove them.
        assertThat(slots()).hasSize(2);
    }

    /**
     * A slot is a pointer, and what it points at can stop being listable after it was placed.
     * The public row drops it; the administrative row keeps it, because an administrator who
     * cannot see a broken placement cannot fix one.
     */
    @Test
    @DisplayName("a cancelled event leaves the public row and stays in the administrator's")
    void aCancelledEventLeavesThePublicRow() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event first = publish(manager, venue, "Still On", NEXT_MONTH);
        Event second = publish(manager, venue, "Also On", NEXT_MONTH.plusDays(1));
        Event doomed = publish(manager, venue, "Called Off", NEXT_MONTH.plusDays(2));

        place(first, second, doomed);
        assertThat(featured()).hasSize(3);

        cancel(manager, doomed);

        // Two left, which is below the minimum, so the row goes entirely rather than showing
        // the remains of a selection.
        assertThat(featured()).isEmpty();
        assertThat(slots()).hasSize(3);
    }

    @Test
    @DisplayName("an event the listing would not show cannot be placed at all")
    void anUnlistableEventIsRefused() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event draft = createEvent(manager, venue.getId(), "Not Published Yet", NEXT_MONTH);

        ResponseEntity<Error> refused = exchange(HttpMethod.PUT, "/admin/featured-slots",
                platformAdmin(), List.of(slotFor(draft)), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().getMessage()).contains("public listing shows");
    }

    /**
     * Position is relative, so the whole row is replaced at once. What that has to mean is that
     * the previous row is gone rather than appended to - the failure this asserts against is a
     * PUT that adds.
     */
    @Test
    @DisplayName("replacing the row replaces it, and renumbers what is left")
    void replacingReplaces() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event a = publish(manager, venue, "First Row A", NEXT_MONTH);
        Event b = publish(manager, venue, "First Row B", NEXT_MONTH.plusDays(1));
        Event c = publish(manager, venue, "First Row C", NEXT_MONTH.plusDays(2));
        Event d = publish(manager, venue, "Second Row D", NEXT_MONTH.plusDays(3));
        Event e = publish(manager, venue, "Second Row E", NEXT_MONTH.plusDays(4));
        Event f = publish(manager, venue, "Second Row F", NEXT_MONTH.plusDays(5));

        place(a, b, c);
        place(d, e, f);

        assertThat(slots()).hasSize(3);
        assertThat(titles(featured()))
                .containsExactly("Second Row D", "Second Row E", "Second Row F");
        assertThat(slots().stream().map(FeaturedSlot::getPosition).toList())
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("curation belongs to the platform - an organizer cannot place their own event")
    void anOrganizerCannotCurate() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event own = publish(manager, venue, "Feature Me", NEXT_MONTH);

        ResponseEntity<Error> refused = exchange(HttpMethod.PUT, "/admin/featured-slots",
                manager, List.of(slotFor(own)), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(slots(platformAdmin())).isEmpty();
    }

    /**
     * criterion 14 asks for curation to be recorded with actor and instant. It is recorded
     * against the Organization whose Event was placed, because that is whose trail somebody
     * would look in - and because audit_entry has no such thing as a platform-level row.
     */
    @Test
    @DisplayName("being featured is recorded against the organization it happened to")
    void curationIsAudited() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event one = publish(manager, venue, "Audited One", NEXT_MONTH);
        Event two = publish(manager, venue, "Audited Two", NEXT_MONTH.plusDays(1));
        Event three = publish(manager, venue, "Audited Three", NEXT_MONTH.plusDays(2));
        UUID organizationId = UUID.fromString(one.getOrganizationId().toString());

        place(one, two, three);

        Long recorded = jdbc.queryForObject(
                "select count(*) from audit_entry where organization_id = ? and action = ?",
                Long.class, organizationId, "EVENT_FEATURED");
        assertThat(recorded).isEqualTo(1L);
    }

    // ---- the ranked row (criteria 15 and 16) ----

    @Test
    @DisplayName("nothing selling is not a chart")
    void nothingSellingIsNotAChart() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        publish(manager, venue, "Unsold One", NEXT_MONTH);
        publish(manager, venue, "Unsold Two", NEXT_MONTH.plusDays(1));

        assertThat(trending()).isEmpty();
    }

    /**
     * The ranking, and the two things it must not say.
     *
     * <p>Ranks run from one and are the position rather than the count. The count itself is
     * never sent: a position says one Event outsold another this week, where a number says what
     * an Organization took, across Organizations, to anybody who loads the page - and sales
     * figures are restricted inside an Organization already (requirements/007 criterion 13).
     */
    @Test
    @DisplayName("events rank by tickets sold, and the figures behind the rank are not published")
    void eventsRankBySales() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);

        // Five, because below five there is no chart at all - which is the next test.
        List<Event> events = List.of(
                publish(manager, venue, "Sold Five", NEXT_MONTH),
                publish(manager, venue, "Sold Four", NEXT_MONTH.plusDays(1)),
                publish(manager, venue, "Sold Three", NEXT_MONTH.plusDays(2)),
                publish(manager, venue, "Sold Two", NEXT_MONTH.plusDays(3)),
                publish(manager, venue, "Sold One", NEXT_MONTH.plusDays(4)));

        for (int index = 0; index < events.size(); index++) {
            int seats = events.size() - index;
            TokenPair buyer = signUp("buyer-" + UUID.randomUUID() + "@example.com");
            buyAndPay(buyer, events.get(index).getId(), seatIdsOf(events.get(index).getId(), seats));
        }

        List<TrendingEvent> chart = trending();

        assertThat(chart.stream().map(entry -> entry.getEvent().getTitle()).toList())
                .containsExactly("Sold Five", "Sold Four", "Sold Three", "Sold Two", "Sold One");
        assertThat(chart.stream().map(TrendingEvent::getRank).toList())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("a chart of four is not shown")
    void aShortChartIsNotShown() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);

        for (int index = 0; index < 4; index++) {
            Event event = publish(manager, venue, "Short Chart " + index,
                    NEXT_MONTH.plusDays(index));
            TokenPair buyer = signUp("buyer-" + UUID.randomUUID() + "@example.com");
            buyAndPay(buyer, event.getId(), seatIdsOf(event.getId(), 1));
        }

        assertThat(trending()).isEmpty();
    }

    // ---- helpers ----

    private List<PublicEventSummary> featured() {
        return List.of(exchange(HttpMethod.GET, "/public/featured-events", null, null,
                PublicEventSummary[].class).getBody());
    }

    private List<TrendingEvent> trending() {
        return List.of(exchange(HttpMethod.GET, "/public/trending-events", null, null,
                TrendingEvent[].class).getBody());
    }

    private List<FeaturedSlot> slots() {
        return slots(platformAdmin());
    }

    private List<FeaturedSlot> slots(TokenPair session) {
        return List.of(exchange(HttpMethod.GET, "/admin/featured-slots", session, null,
                FeaturedSlot[].class).getBody());
    }

    private void place(Event... events) {
        var request = List.of(events).stream().map(FeaturedAndTrendingTest::slotFor).toList();
        var response = exchange(HttpMethod.PUT, "/admin/featured-slots", platformAdmin(),
                request, FeaturedSlot[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static FeaturedSlotInput slotFor(Event event) {
        return new FeaturedSlotInput(event.getId(),
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(7));
    }

    private void cancel(TokenPair manager, Event event) {
        var response = exchange(HttpMethod.POST, "/events/" + event.getId() + "/cancel", manager,
                new com.eventticket.api.model.RefundRequest("Called off"),
                com.eventticket.api.model.EventCancellation.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private static List<String> titles(List<PublicEventSummary> items) {
        return items.stream().map(PublicEventSummary::getTitle).toList();
    }

    private Event publish(TokenPair manager, Venue venue, String title, OffsetDateTime startsAt) {
        Event event = createEvent(manager, venue.getId(), title, startsAt);
        priceTier(manager, event.getId(), "Standard", 250_000);
        var published = publish(manager, event.getId(), Event.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        return published.getBody();
    }

    private Venue venueWithSeats(TokenPair manager) {
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "tp-ho-chi-minh");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 3, 4));
        return venue;
    }

    private TokenPair approvedManager() {
        TokenPair session = signUp("organizer-" + UUID.randomUUID() + "@example.com");
        var organization = createOrganization(session, "Acme Events " + UUID.randomUUID());
        approve(organization);
        return switchTo(session, organization);
    }
}
