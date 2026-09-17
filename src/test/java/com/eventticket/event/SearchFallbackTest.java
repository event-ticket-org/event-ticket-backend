package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.PublicEventPage;
import com.eventticket.api.model.PublicEventSummary;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.event.search.EventDocument;
import com.eventticket.event.search.EventSearchIndex;
import com.eventticket.event.search.SearchQuery;
import com.eventticket.event.search.SearchUnavailableException;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * What the listing does when the search cluster will not answer.
 *
 * <p>requirements/009 criterion 20: whatever serves the listing is derived, and losing it may
 * degrade ordering but must never take the listing down. That is a promise, and a promise
 * nothing has watched fail is a hypothesis - so this runs against an index that throws on
 * every call.
 *
 * <p>A stubbed port rather than a stopped container, because what is being tested is the
 * decision {@code ListPublicEvents} makes, not the adapter's ability to notice a closed socket.
 * The adapter's own error wrapping is exercised wherever the real cluster is.
 *
 * <p>The two halves degrade differently, on purpose. A filter degrades <em>silently</em>: a
 * visitor narrowing by city does not need to know which system answered. An <em>ordering</em>
 * does not: criterion 5 says a client asks for one rather than inferring which it got, so a
 * request for relevance is refused rather than answered chronologically.
 */
@Import(SearchFallbackTest.BrokenIndex.class)
class SearchFallbackTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH =
            OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenIndex {

        /**
         * Every read fails; writes are accepted and discarded.
         *
         * <p>Writes have to succeed or publishing an Event would fail, and a search cluster
         * being down must not stop anybody selling tickets - which is the same guarantee from
         * the other side.
         */
        @Bean
        @Primary
        EventSearchIndex brokenIndex() {
            return new EventSearchIndex() {
                @Override
                public boolean ensureReady() {
                    return false;
                }

                @Override
                public void index(Collection<EventDocument> documents) {
                }

                @Override
                public void delete(Collection<UUID> eventIds) {
                }

                @Override
                public void replaceAll(List<EventDocument> documents) {
                }

                @Override
                public SearchQuery.Results search(SearchQuery.Criteria criteria) {
                    throw new SearchUnavailableException("cluster is down", null);
                }

                @Override
                public boolean isAvailable() {
                    return false;
                }
            };
        }
    }

    @Test
    @DisplayName("the listing still lists, from Postgres")
    void theListingSurvives() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        publish(manager, venue, "Still Findable", NEXT_MONTH);

        assertThat(titles(listing(Map.of()))).contains("Still Findable");
    }

    @Test
    @DisplayName("filters still filter, and say nothing about which system answered")
    void filtersStillFilter() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        publish(manager, venue, "Fallback Music", "nhac-song", NEXT_MONTH);
        publish(manager, venue, "Fallback Theatre", "san-khau-nghe-thuat", NEXT_MONTH);

        assertThat(titles(listing(Map.of("q", "fallback", "categorySlug", "nhac-song"))))
                .containsExactly("Fallback Music");
        assertThat(titles(listing(Map.of("q", "fallback", "citySlug", "da-nang")))).isEmpty();
    }

    /**
     * The counts survive too, from a {@code GROUP BY} rather than from an aggregation. A strip
     * of chips that lost its numbers the moment the cluster hiccuped would be a visible outage
     * in a feature that is supposed to degrade invisibly.
     */
    @Test
    @DisplayName("facet counts survive, computed by Postgres")
    void facetsSurvive() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        publish(manager, venue, "Counted Anyway", "nhac-song", NEXT_MONTH);

        var page = exchange(HttpMethod.GET, "/public/events?q={q}", null, null,
                PublicEventPage.class, Map.of("q", "counted anyway")).getBody();

        assertThat(page.getCategoryFacets()).hasSize(6);
        assertThat(page.getCategoryFacets()).anyMatch(
                facet -> facet.getSlug().equals("nhac-song") && facet.getCount() == 1);
    }

    /**
     * An ordering is not a filter. Criterion 5 says a client asks for one rather than inferring
     * which it got, so relevance is refused here - answering chronologically would satisfy the
     * request and lose the information that it was not honoured.
     */
    @Test
    @DisplayName("relevance is refused rather than silently downgraded to start time")
    void relevanceIsRefusedRatherThanDowngraded() {
        ResponseEntity<Error> refused = exchange(HttpMethod.GET,
                "/public/events?sort=RELEVANCE&q={q}", null, null, Error.class,
                Map.of("q", "anything"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().getMessage()).contains("not available right now");
    }

    // ---- helpers ----

    private List<PublicEventSummary> listing(Map<String, String> parameters) {
        var ordered = new java.util.LinkedHashMap<>(parameters);
        String path = "/public/events?"
                + String.join("&", ordered.keySet().stream().map(k -> k + "={" + k + "}").toList());
        return exchange(HttpMethod.GET, path, null, null, PublicEventPage.class, ordered)
                .getBody().getItems();
    }

    private static List<String> titles(List<PublicEventSummary> items) {
        return items.stream().map(PublicEventSummary::getTitle).toList();
    }

    private Event publish(TokenPair manager, Venue venue, String title, OffsetDateTime startsAt) {
        return publish(manager, venue, title, A_CATEGORY, startsAt);
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

    private Venue venueWithSeats(TokenPair manager) {
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "tp-ho-chi-minh");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 2));
        return venue;
    }

    private TokenPair approvedManager() {
        TokenPair session = signUp("organizer-" + UUID.randomUUID() + "@example.com");
        var organization = createOrganization(session, "Acme Events " + UUID.randomUUID());
        approve(organization);
        return switchTo(session, organization);
    }
}
