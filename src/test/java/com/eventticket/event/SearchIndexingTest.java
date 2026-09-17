package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.event.search.ElasticsearchEventIndex;
import com.eventticket.event.search.outbox.IndexPendingEvents;
import com.eventticket.event.search.outbox.RebuildSearchIndex;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The index, against a real Elasticsearch.
 *
 * <p>What is worth testing here is not that a document can be written - it is the three places
 * this design can be wrong in a way nothing else would notice:
 *
 * <ul>
 *   <li>the analyzer, because the whole comparison with Postgres rests on the claim that
 *       {@code asciifolding} folds what {@code unaccent} folds;</li>
 *   <li>the delete branch, because an Event that stops being listable and stays searchable is
 *       an index that only ever grows and a listing that disagrees with its own search;</li>
 *   <li>the rebuild, because it is the reconciler that makes a forgotten call site recoverable
 *       - and a reconciler nobody has watched work is a hypothesis.</li>
 * </ul>
 *
 * <p>The drain is invoked directly rather than waited for. It runs on a two-second schedule in
 * a deployment; a test that slept for it would be slow and, worse, occasionally green for the
 * wrong reason.
 */
class SearchIndexingTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH =
            OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Autowired
    private IndexPendingEvents indexPendingEvents;

    @Autowired
    private RebuildSearchIndex rebuildSearchIndex;

    @Autowired
    private ElasticsearchClient elasticsearch;

    @Test
    @DisplayName("publishing an event puts it in the index, with the fields a card is drawn from")
    void publishingIndexes() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event event = publish(manager, venue, "Đêm Nhạc Cuối Năm", NEXT_MONTH);

        indexPendingEvents.drain();

        var document = documentFor(event.getId());
        assertThat(document).isNotNull();
        assertThat(document.get("title")).isEqualTo("Đêm Nhạc Cuối Năm");
        assertThat(document.get("citySlug")).isEqualTo("tp-ho-chi-minh");
        assertThat(document.get("categorySlug")).isEqualTo("nhac-song");
        assertThat(document.get("priceFrom")).isEqualTo(250_000);
    }

    /**
     * The claim the whole Postgres comparison rests on.
     *
     * <p>`unaccent` folds the entire Vietnamese range including Đ to D, which is the character
     * worth checking because it is a distinct letter rather than a diacritic and a folding table
     * could reasonably leave it alone. If `asciifolding` disagreed, the two search paths would
     * answer differently for the same query and the benchmark would be comparing two things.
     */
    @Test
    @DisplayName("the analyzer folds what unaccent folds, including Đ")
    void theAnalyzerFoldsLikeUnaccent() throws IOException {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        publish(manager, venue, "Đêm Nhạc Cuối Năm", NEXT_MONTH);
        indexPendingEvents.drain();

        assertThat(matches("dem nhac")).isEqualTo(1);
        assertThat(matches("DEM NHAC")).isEqualTo(1);
        assertThat(matches("Đêm Nhạc")).isEqualTo(1);
        // Đ folded to D, which Java's own normalizer would not do.
        assertThat(matches("cuoi nam")).isEqualTo(1);
        assertThat(matches("khong co gi")).isZero();
    }

    /**
     * An Event the listing stops showing must stop being searchable, or the index grows forever
     * and search disagrees with the listing it is supposed to serve.
     */
    @Test
    @DisplayName("an event that stops being listable is removed from the index")
    void unlistingRemoves() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event event = publish(manager, venue, "Briefly Listed", NEXT_MONTH);
        indexPendingEvents.drain();
        assertThat(documentFor(event.getId())).isNotNull();

        var patch = new com.eventticket.api.model.EventPatch();
        patch.setListed(false);
        exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager, patch, Event.class);
        indexPendingEvents.drain();

        assertThat(documentFor(event.getId())).isNull();
    }

    /**
     * The reconciler. It is what makes a forgotten {@code SearchOutbox.changed} recoverable
     * rather than permanent, so it is asserted against an index that has been deliberately
     * corrupted - deleted out from under the application, which is the shape of the failure it
     * exists for.
     */
    @Test
    @DisplayName("a rebuild restores an index that was lost, and swaps it in behind the alias")
    void rebuildRestoresALostIndex() throws IOException {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event event = publish(manager, venue, "Survives A Rebuild", NEXT_MONTH);
        indexPendingEvents.drain();

        // Everything gone, as if the volume had been lost. Deleted by concrete index rather
        // than by the alias, which Elasticsearch refuses - the alias is what the application
        // uses and is not itself a thing that can be removed this way.
        var behind = elasticsearch.indices()
                .getAlias(alias -> alias.name(ElasticsearchEventIndex.ALIAS)).aliases().keySet();
        elasticsearch.indices().delete(delete -> delete.index(List.copyOf(behind)));

        int rebuilt = rebuildSearchIndex.rebuild();

        assertThat(rebuilt).isGreaterThanOrEqualTo(1);
        assertThat(documentFor(event.getId())).isNotNull();
    }

    /**
     * The failure this test exists for, reproduced exactly.
     *
     * <p>A deployment whose Events all predate the outbox: they are published, nothing has
     * touched them since, so the queue is empty and the drain returns before it ever reaches
     * {@code ensureReady}. Every part behaves as written and there is no index at all - which
     * is what the first real deployment of this feature looked like: ten published events and
     * an empty cluster.
     *
     * <p>So the assertion is made from that state rather than from a convenient one - index
     * gone, queue empty, and only then the thing that runs at boot.
     */
    @Test
    @DisplayName("a deployment with events but an empty outbox builds an index at startup")
    void startupBuildsTheFirstIndex() throws IOException {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        Event event = publish(manager, venue, "Predates The Outbox", NEXT_MONTH);

        // The state a first deployment is in: the Event exists and nothing is queued about it.
        indexPendingEvents.drain();
        var behind = elasticsearch.indices()
                .getAlias(alias -> alias.name(ElasticsearchEventIndex.ALIAS)).aliases().keySet();
        elasticsearch.indices().delete(delete -> delete.index(List.copyOf(behind)));
        jdbc.update("delete from search_outbox");

        rebuildSearchIndex.buildOnFirstStart();

        assertThat(documentFor(event.getId())).isNotNull();
    }

    /** ...and an ordinary restart, with an index already there, leaves it alone. */
    @Test
    @DisplayName("a restart with an index already present does not rebuild it")
    void startupLeavesAnExistingIndexAlone() throws IOException {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager);
        publish(manager, venue, "Already Indexed", NEXT_MONTH);
        indexPendingEvents.drain();

        var before = elasticsearch.indices()
                .getAlias(alias -> alias.name(ElasticsearchEventIndex.ALIAS)).aliases().keySet();

        rebuildSearchIndex.buildOnFirstStart();

        // The same concrete index behind the alias: a rebuild would have replaced it.
        var after = elasticsearch.indices()
                .getAlias(alias -> alias.name(ElasticsearchEventIndex.ALIAS)).aliases().keySet();
        assertThat(after).isEqualTo(before);
    }

    // ---- helpers ----


    private java.util.Map<String, Object> documentFor(UUID eventId) {
        try {
            var response = elasticsearch.get(get -> get
                    .index(ElasticsearchEventIndex.ALIAS).id(eventId.toString()),
                    java.util.Map.class);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> source =
                    (java.util.Map<String, Object>) response.source();
            return response.found() ? source : null;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private long matches(String query) throws IOException {
        return elasticsearch.count(count -> count
                .index(ElasticsearchEventIndex.ALIAS)
                .query(q -> q.multiMatch(match -> match
                        .query(query)
                        .fields("title", "description", "venueName", "organizationName"))))
                .count();
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
        SeatMap map = SeatMaps.block("Standard", 2, 2);
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
