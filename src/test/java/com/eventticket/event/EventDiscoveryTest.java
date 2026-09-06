package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.PublicEventPage;
import com.eventticket.api.model.PublicEventSummary;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * requirements/009 criteria 3, 4, 5 and 11 - the two things the listing gained.
 *
 * <p>Separate from {@link EventLifecycleTest}, which owns what the listing <em>contains</em>.
 * This owns what it can be asked and what it says about each entry.
 */
class EventDiscoveryTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH =
            OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    // ---- the text filter (criterion 4) ----

    @Test
    @DisplayName("the text filter matches part of a title, whatever the case")
    void theFilterMatchesPartOfATitle() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Live in Saigon", NEXT_MONTH);
        publish(manager, venue, "Jazz at the Opera House", NEXT_MONTH);

        assertThat(titles(search("saigon"))).containsExactly("Live in Saigon");
        assertThat(titles(search("SAIGON"))).containsExactly("Live in Saigon");
        assertThat(titles(search("opera"))).containsExactly("Jazz at the Opera House");
        assertThat(titles(search("neither"))).isEmpty();
        assertThat(titles(search(""))).hasSize(2);
    }

    /**
     * The reason the folding is done by the database on both sides of the comparison rather
     * than in Java on one of them.
     *
     * <p>Somebody hunting for "Đêm Nhạc Cuối Năm" types "dem nhac" - on a phone keyboard, in a
     * hurry - and this market writes with diacritics. The reverse matters just as much and is
     * the half that was broken first: with the column unaccented and the pattern not, typing
     * the title exactly as it is written found nothing at all.
     */
    @Test
    @DisplayName("accents are folded on both sides, so either spelling finds the other")
    void accentsAreFoldedBothWays() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Đêm Nhạc Cuối Năm", NEXT_MONTH);

        assertThat(titles(search("dem nhac"))).containsExactly("Đêm Nhạc Cuối Năm");
        assertThat(titles(search("Đêm Nhạc"))).containsExactly("Đêm Nhạc Cuối Năm");
        assertThat(titles(search("DEM NHAC"))).containsExactly("Đêm Nhạc Cuối Năm");
        // Đ is a distinct letter rather than a diacritic, so it is the character a folding
        // table could reasonably leave alone. Postgres folds it; Java's normalizer does not.
        assertThat(titles(search("cuoi nam"))).containsExactly("Đêm Nhạc Cuối Năm");
    }

    /**
     * requirements/009 criterion 5, which is the reason this endpoint takes a filter and not a
     * search.
     *
     * <p>"Rock" is an exact title and starts later; "Rock and Roll Revival" merely contains the
     * word and starts sooner. Every relevance scheme ever written puts the exact match first.
     * This must not: the order is the order events happen in, and nothing else.
     */
    @Test
    @DisplayName("a match never changes the order - the soonest event is still first")
    void matchingDoesNotReorder() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Rock", NEXT_MONTH.plusDays(10));
        publish(manager, venue, "Rock and Roll Revival", NEXT_MONTH);

        assertThat(titles(search("rock")))
                .containsExactly("Rock and Roll Revival", "Rock");
    }

    /**
     * The pattern is ours; the text in it is the caller's. Without escaping, a search for a
     * discount matches the entire listing, which reads as the filter being broken rather than
     * as a character having quietly meant something.
     */
    @Test
    @DisplayName("wildcards typed by a visitor are text, not wildcards")
    void wildcardsAreLiteral() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Live in Saigon", NEXT_MONTH);
        publish(manager, venue, "50% Off Night", NEXT_MONTH);

        assertThat(titles(search("%"))).containsExactly("50% Off Night");
        assertThat(titles(search("50%"))).containsExactly("50% Off Night");
        // `_` matches any single character in LIKE, so an unescaped one would match both.
        assertThat(titles(search("_"))).isEmpty();
    }

    @Test
    @DisplayName("the text filter narrows the city filter rather than replacing it")
    void theFiltersCompose() {
        TokenPair manager = approvedManager();
        Venue saigon = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, saigon, "Live in Saigon", NEXT_MONTH);

        assertThat(listing(Map.of("q", "live", "city", "Ho Chi Minh City"))).hasSize(1);
        assertThat(listing(Map.of("q", "live", "city", "Da Nang"))).isEmpty();
        assertThat(listing(Map.of("q", "nothing", "city", "Ho Chi Minh City"))).isEmpty();
    }

    // ---- how many seats are left (criteria 3 and 11) ----

    @Test
    @DisplayName("an entry says how many seats are still on sale")
    void anEntrySaysWhatIsLeft() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 3));
        Event event = publish(manager, venue, "Six Seater", NEXT_MONTH);

        assertThat(only(search("six")).getSeatsAvailable()).isEqualTo(6);
        // And the event's own page agrees, from the same query - a buyer looking at both must
        // not see two different numbers.
        assertThat(publicEvent(event.getId()).getSeatsAvailable()).isEqualTo(6);
    }

    /**
     * A held seat is not available. The hold expires, but somebody told "one left" who then
     * finds none has been misled on a technicality - and the seat map the listing links to
     * already draws a held seat as unavailable, so saying otherwise here would make the two
     * pages disagree.
     */
    @Test
    @DisplayName("seats held in somebody's checkout are not available")
    void heldSeatsAreNotAvailable() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publish(manager, venue, "Held Show", NEXT_MONTH);

        TokenPair buyer = signUp("buyer-" + UUID.randomUUID() + "@example.com");
        checkout(buyer, event.getId(), seatIdsOf(event.getId(), 2));

        assertThat(only(search("held")).getSeatsAvailable()).isEqualTo(2);
    }

    @Test
    @DisplayName("sold seats are gone, and a sold-out event still appears saying so")
    void aSoldOutEventStillAppears() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 1, 2));
        Event event = publish(manager, venue, "Sold Out Show", NEXT_MONTH);

        TokenPair buyer = signUp("buyer-" + UUID.randomUUID() + "@example.com");
        Order order = buyAndPay(buyer, event.getId(), seatIdsOf(event.getId(), 2));
        assertThat(order.getStatus()).isEqualTo(com.eventticket.api.model.OrderStatus.PAID);

        // Criterion 11: still listed. Hiding it would make the listing disagree with what is
        // on, and somebody who arrived too late is better told so.
        PublicEventSummary listed = only(search("sold out"));
        assertThat(listed.getSeatsAvailable()).isZero();
    }

    // ---- helpers ----

    private List<PublicEventSummary> search(String query) {
        return listing(Map.of("q", query));
    }

    private List<PublicEventSummary> listing(Map<String, String> parameters) {
        var ordered = new LinkedHashMap<>(parameters);
        String path = "/public/events?"
                + String.join("&", ordered.keySet().stream().map(k -> k + "={" + k + "}").toList());
        return exchange(HttpMethod.GET, path, null, null, PublicEventPage.class, ordered)
                .getBody().getItems();
    }

    private static List<String> titles(List<PublicEventSummary> items) {
        return items.stream().map(PublicEventSummary::getTitle).toList();
    }

    private static PublicEventSummary only(List<PublicEventSummary> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private com.eventticket.api.model.PublicEvent publicEvent(UUID eventId) {
        return exchange(HttpMethod.GET, "/public/events/" + eventId, null, null,
                com.eventticket.api.model.PublicEvent.class).getBody();
    }

    private Event publish(TokenPair manager, Venue venue, String title, OffsetDateTime startsAt) {
        Event event = createEvent(manager, venue.getId(), title, startsAt);
        priceTier(manager, event.getId(), "Standard", 250_000);
        var published = publish(manager, event.getId(), Event.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        return published.getBody();
    }

    private Venue venueWithSeats(TokenPair manager, SeatMap map) {
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), map);
        return venue;
    }

    private TokenPair approvedManager() {
        TokenPair session = signUp("organizer-" + UUID.randomUUID() + "@example.com");
        var organization = createOrganization(session, "Acme Events " + UUID.randomUUID());
        approve(organization);
        return switchTo(session, organization);
    }
}
