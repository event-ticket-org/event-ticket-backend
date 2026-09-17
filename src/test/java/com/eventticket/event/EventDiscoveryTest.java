package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
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
import org.springframework.http.ResponseEntity;

/**
 * requirements/009 criteria 3, 4, 5, 11, 12, 17 and 18.
 *
 * <p>Separate from {@link EventLifecycleTest}, which owns what the listing <em>contains</em>.
 * This owns what it can be asked and what it says about each entry.
 */
class EventDiscoveryTest extends ApiTest {

    /**
     * Driven directly, because the drain runs on a schedule the suite turns off. A test that
     * waited two seconds for a tick would be slow and occasionally green for the wrong reason.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.eventticket.event.search.outbox.IndexPendingEvents indexPendingEvents;

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
     * requirements/009 criterion 5: start time is the default ordering, and the only one
     * available without a text query.
     *
     * <p>"Rock" is an exact title and starts later; "Rock and Roll Revival" merely contains the
     * word and starts sooner. Every relevance scheme ever written puts the exact match first.
     * The default must not: asking for a match narrows the listing and never reorders it.
     *
     * <p>This test used to exist because the listing had no other ordering at all. It now exists
     * because the other one has to be asked for - the more useful assertion of the two, since a
     * relevance implementation leaking into the default would have passed the old version by
     * accident.
     */
    @Test
    @DisplayName("a match never changes the default order - the soonest event is still first")
    void matchingDoesNotReorder() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Rock", NEXT_MONTH.plusDays(10));
        publish(manager, venue, "Rock and Roll Revival", NEXT_MONTH);

        assertThat(titles(search("rock")))
                .containsExactly("Rock and Roll Revival", "Rock");
    }

    /**
     * A visitor's punctuation is never a wildcard, and the reason changed underneath this test.
     *
     * <p>Against Postgres, `%` and `_` were LIKE's own syntax and had to be escaped or a search
     * for a discount matched the whole listing. The index has no such syntax: the analyzer
     * drops punctuation before anything is matched, so `%` carries no term at all and finds
     * nothing, while `50%` carries the term `50` and finds the event.
     *
     * <p>That is a real behaviour change and it is asserted rather than smoothed over. Both
     * systems refuse to treat a visitor's punctuation as an operator; only one of them ever
     * could have, and the fallback path still escapes for exactly that reason.
     */
    @Test
    @DisplayName("punctuation a visitor types is never an operator")
    void wildcardsAreLiteral() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Live in Saigon", NEXT_MONTH);
        publish(manager, venue, "50% Off Night", NEXT_MONTH);

        // The term survives the punctuation around it.
        assertThat(titles(search("50%"))).containsExactly("50% Off Night");
        // ...and punctuation on its own is not a term, so it matches nothing rather than
        // everything. Under LIKE this was the escaping's job; here there is nothing to escape.
        assertThat(titles(search("%"))).isEmpty();
        assertThat(titles(search("_"))).isEmpty();
    }

    @Test
    @DisplayName("the text filter narrows the city filter rather than replacing it")
    void theFiltersCompose() {
        TokenPair manager = approvedManager();
        Venue saigon = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, saigon, "Live in Saigon", NEXT_MONTH);

        assertThat(listing(Map.of("q", "live", "citySlug", "tp-ho-chi-minh"))).hasSize(1);
        assertThat(listing(Map.of("q", "live", "citySlug", "da-nang"))).isEmpty();
        assertThat(listing(Map.of("q", "nothing", "citySlug", "tp-ho-chi-minh"))).isEmpty();
    }

    // ---- the Category taxonomy (criteria 12 and 17) ----

    @Test
    @DisplayName("the listing filters by category, and composes with everything else")
    void theCategoryFilterNarrows() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Live in Saigon", "nhac-song", NEXT_MONTH);
        publish(manager, venue, "Hamlet", "san-khau-nghe-thuat", NEXT_MONTH);

        assertThat(titles(listing(Map.of("categorySlug", "nhac-song"))))
                .containsExactly("Live in Saigon");
        assertThat(titles(listing(Map.of("categorySlug", "san-khau-nghe-thuat"))))
                .containsExactly("Hamlet");
        assertThat(listing(Map.of("categorySlug", "the-thao"))).isEmpty();
        assertThat(listing(Map.of("categorySlug", "nhac-song", "q", "hamlet"))).isEmpty();
    }

    /**
     * The application cannot extend the taxonomy - V14 revokes the writes - so the only way to
     * reach an unknown Category is to name one, and the answer says which set to choose from.
     * A foreign key violation would reach the caller as "the request could not be completed",
     * which is true of a database error and useless to somebody who mistyped.
     */
    @Test
    @DisplayName("a category nobody defined is refused by name, not by foreign key")
    void anUnknownCategoryIsRefused() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));

        var input = new com.eventticket.api.model.EventInput("Unfiled", venue.getId(),
                "not-a-category", NEXT_MONTH);
        ResponseEntity<Error> refused =
                exchange(HttpMethod.POST, "/events", manager, input, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().getMessage()).contains("/public/categories");
    }

    /**
     * Criterion 17. The counts are taken under the other filters and <em>without</em> the
     * category filter - the number a visitor is choosing between, not the number of the page
     * they are already on.
     *
     * <p>The zero is the half worth asserting. A Category shown greyed with a zero beside it
     * tells a visitor their other filters emptied it; one missing from the array reads as one
     * that does not exist, and a GROUP BY produces exactly that missing row unless something
     * fills it in.
     */
    @Test
    @DisplayName("facet counts cover every category, including the ones matching nothing")
    void facetsCountEveryCategory() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Facet Music One", "nhac-song", NEXT_MONTH);
        publish(manager, venue, "Facet Music Two", "nhac-song", NEXT_MONTH.plusDays(1));
        publish(manager, venue, "Facet Play", "san-khau-nghe-thuat", NEXT_MONTH);

        Map<String, Integer> counts = facetsOf(Map.of("q", "facet"));

        assertThat(counts).containsEntry("nhac-song", 2)
                .containsEntry("san-khau-nghe-thuat", 1)
                .containsEntry("the-thao", 0)
                .containsEntry("khac", 0);
        // Every Category, so a client draws the whole strip from one response.
        assertThat(counts).hasSize(6);
    }

    /**
     * The counts ignore the category filter and nothing else. Narrowing to one Category must
     * leave the other counts exactly where they were, or the strip collapses to a single
     * non-zero number the moment somebody uses it - and there is then no way back.
     */
    @Test
    @DisplayName("choosing a category does not change the counts beside it")
    void facetsIgnoreTheCategoryFilterOnly() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Sticky Music", "nhac-song", NEXT_MONTH);
        publish(manager, venue, "Sticky Play", "san-khau-nghe-thuat", NEXT_MONTH);

        Map<String, Integer> unfiltered = facetsOf(Map.of("q", "sticky"));
        Map<String, Integer> narrowed =
                facetsOf(Map.of("q", "sticky", "categorySlug", "nhac-song"));

        assertThat(narrowed).isEqualTo(unfiltered);
        assertThat(narrowed).containsEntry("san-khau-nghe-thuat", 1);
    }

    // ---- what the text query covers (criterion 18) ----

    /**
     * Criterion 18 widened the query from the title to four fields. Only the title is indexed,
     * which is a performance decision rather than a behavioural one - a description has to
     * match either way, and this is what says so.
     */
    @Test
    @DisplayName("the query matches the description, the venue and the organizer, not only the title")
    void theQuerySpansFourFields() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publish(manager, venue, "Untitled Evening", NEXT_MONTH);

        var patch = new com.eventticket.api.model.EventPatch();
        patch.setDescription("An evening of xylophone music");
        exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager, patch, Event.class);
        // Changed after publishing, so the index has to be told. The shared publish helper
        // drains for the publish itself; nothing can do it automatically for a later edit
        // without putting a drain inside the read.
        indexPendingEvents();

        assertThat(titles(search("xylophone"))).containsExactly("Untitled Evening");
        // The Venue's name and the Organization's name, neither of which is on the Event.
        assertThat(titles(search("Hoa Binh"))).contains("Untitled Evening");
        assertThat(titles(search("Acme"))).contains("Untitled Evening");
    }

    // ---- the second ordering, and what is not built yet (criterion 5) ----

    /**
     * Criterion 5's second ordering, now that something can compute it.
     *
     * <p>This is the exact pair {@code matchingDoesNotReorder} uses, asserted the other way
     * round, and the two together are what say the orderings are real rather than nominal:
     * "Rock" is an exact title and starts later, "Rock and Roll Revival" merely contains the
     * word and starts sooner. By start time the Revival is first. By relevance the exact match
     * is - and if either assertion could hold with the other's implementation, neither is
     * testing anything.
     */
    @Test
    @DisplayName("relevance puts the exact match first, where start time puts the soonest first")
    void relevanceReordersWhenAskedFor() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publish(manager, venue, "Rock", NEXT_MONTH.plusDays(10));
        publish(manager, venue, "Rock and Roll Revival", NEXT_MONTH);
        indexPendingEvents.drain();

        assertThat(titles(search("rock")))
                .containsExactly("Rock and Roll Revival", "Rock");
        assertThat(titles(listing(Map.of("q", "rock", "sort", "RELEVANCE"))))
                .containsExactly("Rock", "Rock and Roll Revival");
    }

    @Test
    @DisplayName("relevance to nothing is refused as the contradiction it is")
    void relevanceNeedsSomethingToBeRelevantTo() {
        ResponseEntity<Error> refused = exchange(HttpMethod.GET,
                "/public/events?sort=RELEVANCE", null, null, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().getMessage()).contains("Send a search term");
    }

    /**
     * The curated and ranked rows answer an empty array when there is nothing to show, which
     * for those two is the truth rather than a placeholder - there is no such thing as a
     * featured row that exists and holds nothing.
     *
     * <p>They answered 501 until they were built, and this test is what said so. It now says
     * the opposite, which is the point of having written it: {@link FeaturedAndTrendingTest}
     * owns what they do when there is something to show.
     */
    @Test
    @DisplayName("the curated and ranked rows are empty rather than absent when nothing qualifies")
    void theRowsAreEmptyRatherThanAbsent() {
        assertThat(exchange(HttpMethod.GET, "/public/featured-events", null, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.GET, "/public/trending-events", null, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- the vocabulary itself (criteria 12 and 13) ----

    @Test
    @DisplayName("the category and city sets are readable without an account")
    void theVocabularyIsPublic() {
        var categories = exchange(HttpMethod.GET, "/public/categories", null, null,
                com.eventticket.api.model.Category[].class).getBody();
        var cities = exchange(HttpMethod.GET, "/public/cities", null, null,
                com.eventticket.api.model.City[].class).getBody();

        assertThat(categories).extracting(com.eventticket.api.model.Category::getSlug)
                .containsExactly("nhac-song", "san-khau-nghe-thuat", "the-thao",
                        "hoi-thao-workshop", "tham-quan-trai-nghiem", "khac");
        // Vietnamese display names, which is why the name is a column rather than an enum value.
        assertThat(categories[0].getName()).isEqualTo("Nhạc sống");
        assertThat(cities).extracting(com.eventticket.api.model.City::getSlug)
                .contains("ha-noi", "tp-ho-chi-minh", "da-nang");
    }

    // ---- how many seats are left (criteria 3 and 11) ----

    @Test
    @DisplayName("an entry says how many seats are still on sale, out of how many there were")
    void anEntrySaysWhatIsLeft() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 3));
        Event event = publish(manager, venue, "Six Seater", NEXT_MONTH);

        PublicEventSummary listed = only(search("six"));
        assertThat(listed.getSeatsTotal()).isEqualTo(6);
        assertThat(listed.getSeatsAvailable()).isEqualTo(6);
        // And the event's own page agrees, from the same query - a buyer looking at both must
        // not see two different numbers.
        assertThat(publicEvent(event.getId()).getSeatsAvailable()).isEqualTo(6);
        assertThat(publicEvent(event.getId()).getSeatsTotal()).isEqualTo(6);
    }

    /**
     * The two numbers have to differ somewhere, or nothing here would notice them being
     * swapped: both are integers, so the compiler is no help and a full room reads the same
     * either way round.
     */
    @Test
    @DisplayName("the total stays put as seats sell, and the two are not the same number")
    void theTotalDoesNotMoveWhenSeatsSell() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 3));
        Event event = publish(manager, venue, "Selling Six", NEXT_MONTH);

        TokenPair buyer = signUp("buyer-" + UUID.randomUUID() + "@example.com");
        buyAndPay(buyer, event.getId(), seatIdsOf(event.getId(), 2));

        PublicEventSummary listed = only(search("selling six"));
        assertThat(listed.getSeatsTotal()).isEqualTo(6);
        assertThat(listed.getSeatsAvailable()).isEqualTo(4);
    }

    /**
     * requirements/003 criteria 4 and 11, and the reason the total is "on sale" rather than
     * "in the room": a seat an organizer held back was never available to anybody, so counting
     * it would make every event with a withheld row look emptier than it is.
     */
    @Test
    @DisplayName("seats withheld from sale are in neither number")
    void withheldSeatsAreNotCounted() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 3));
        Event event = publish(manager, venue, "Withholding Six", NEXT_MONTH);

        var patch = new com.eventticket.api.model.EventPatch();
        patch.setUnsellableSeatIds(seatIdsOf(event.getId(), 2));
        exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager, patch, Event.class);

        PublicEventSummary listed = only(search("withholding"));
        assertThat(listed.getSeatsTotal()).isEqualTo(4);
        assertThat(listed.getSeatsAvailable()).isEqualTo(4);
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

    private Map<String, Integer> facetsOf(Map<String, String> parameters) {
        var ordered = new LinkedHashMap<>(parameters);
        String path = "/public/events?"
                + String.join("&", ordered.keySet().stream().map(k -> k + "={" + k + "}").toList());
        var facets = exchange(HttpMethod.GET, path, null, null, PublicEventPage.class, ordered)
                .getBody().getCategoryFacets();
        var counts = new LinkedHashMap<String, Integer>();
        facets.forEach(facet -> counts.put(facet.getSlug(), facet.getCount()));
        return counts;
    }

    private Event publish(TokenPair manager, Venue venue, String title, String categorySlug,
                          OffsetDateTime startsAt) {
        Event event = createEvent(manager, venue.getId(), title, categorySlug, startsAt,
                startsAt.minusHours(1), startsAt.plusHours(4));
        priceTier(manager, event.getId(), "Standard", 250_000);
        var published = publish(manager, event.getId(), Event.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        return published.getBody();
    }

    private Event publish(TokenPair manager, Venue venue, String title, OffsetDateTime startsAt) {
        Event event = createEvent(manager, venue.getId(), title, startsAt);
        priceTier(manager, event.getId(), "Standard", 250_000);
        var published = publish(manager, event.getId(), Event.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        return published.getBody();
    }

    private Venue venueWithSeats(TokenPair manager, SeatMap map) {
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "tp-ho-chi-minh");
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
