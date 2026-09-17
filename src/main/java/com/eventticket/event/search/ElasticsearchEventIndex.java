package com.eventticket.event.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexSettingsAnalysis;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The adapter. Everything Elasticsearch-shaped in this application is in this file.
 *
 * <p><strong>Alias, never a concrete index.</strong> Writers and readers use
 * {@value #ALIAS}; the documents live in a dated index behind it. That is not foresight for its
 * own sake - changing an analyzer means rebuilding, and rebuilding in place means a window
 * where the listing has half an index. The swap is one atomic call.
 */
public class ElasticsearchEventIndex implements EventSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchEventIndex.class);

    /**
     * What everything reads and writes. Never an index name.
     *
     * <p><strong>Prefixed, because this cluster will not only be ours.</strong> Elasticsearch
     * has no schemas and no databases - there is nothing between a cluster and an index - so
     * the only way two systems share one is a naming convention neither of them breaks. An ELK
     * stack claims {@code logs-*}, {@code metrics-*} and {@code traces-*}; Kibana claims
     * {@code .kibana*}; this application claims {@code eventticket-*} and touches nothing else.
     *
     * <p>It was {@code events} for one commit, which is exactly the kind of name that is
     * obviously fine until something else wants it.
     */
    public static final String ALIAS = "eventticket-events";

    /**
     * The analyzer, and the reason it is the boring one.
     *
     * <p>Postgres folds accents with {@code unaccent}, so "ha noi" finds "Hà Nội" and the title
     * typed exactly as written finds itself. {@code asciifolding} does the same job, which
     * makes the comparison between the two an apples-to-apples one: any difference the
     * benchmark finds is ranking and multi-field search rather than normalisation.
     *
     * <p>ICU folding and a Vietnamese segmenter are both better, and both are a second step
     * taken against a measurement rather than a first step taken on principle. Vietnamese
     * writes multi-syllable words with spaces, so a standard tokenizer splits <em>sân khấu</em>
     * into two tokens - which costs precision, and costs it in a way that shows up in the
     * fixed query set rather than in an opinion.
     */
    private static final String ANALYZER = "vi_folded";

    private final ElasticsearchClient client;

    public ElasticsearchEventIndex(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void ensureReady() {
        try {
            if (client.indices().existsAlias(exists -> exists.name(ALIAS)).value()) {
                return;
            }
            String index = newIndexName();
            create(index);
            client.indices().updateAliases(update -> update
                    .actions(Action.of(action -> action.add(add -> add.index(index).alias(ALIAS)))));
            log.info("Created search index index={} alias={}", index, ALIAS);
        } catch (IOException e) {
            throw new SearchUnavailableException("Could not prepare the search index", e);
        }
    }

    @Override
    public void index(Collection<EventDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (EventDocument document : documents) {
            // The document id is the Event id, which is what makes this an upsert and makes a
            // replayed notification free.
            bulk.operations(BulkOperation.of(operation -> operation
                    .index(into -> into.index(ALIAS).id(document.id().toString()).document(document))));
        }
        send(bulk, documents.size(), "indexed");
    }

    @Override
    public void delete(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (UUID id : eventIds) {
            bulk.operations(BulkOperation.of(operation -> operation
                    .delete(from -> from.index(ALIAS).id(id.toString()))));
        }
        // A delete for a document that was never indexed answers 404 per item and is not an
        // error: the consumer re-reads an Event and cannot know whether the last thing it did
        // was index it or delete it, which is exactly the property that makes it convergent.
        send(bulk, eventIds.size(), "deleted");
    }

    @Override
    public void replaceAll(List<EventDocument> documents) {
        try {
            String index = newIndexName();
            create(index);
            if (!documents.isEmpty()) {
                BulkRequest.Builder bulk = new BulkRequest.Builder();
                for (EventDocument document : documents) {
                    bulk.operations(BulkOperation.of(operation -> operation.index(
                            into -> into.index(index).id(document.id().toString()).document(document))));
                }
                send(bulk, documents.size(), "reindexed");
            }

            // Add and remove in one request, so there is no instant where the alias points at
            // both indexes or at neither. A reader mid-request sees one or the other, never a
            // union of the two.
            // The indexes the alias points at right now - normally one, and more than one only
            // if a previous rebuild died between the add and the remove. Removing all of them
            // is what makes that state self-correcting rather than permanent.
            List<String> replaced = List.copyOf(
                    client.indices().getAlias(alias -> alias.name(ALIAS)).aliases().keySet());
            client.indices().updateAliases(update -> {
                update.actions(Action.of(action -> action.add(add -> add.index(index).alias(ALIAS))));
                replaced.forEach(old -> update.actions(
                        Action.of(action -> action.remove(remove -> remove.index(old).alias(ALIAS)))));
                return update;
            });
            for (String old : replaced) {
                client.indices().delete(delete -> delete.index(old));
            }
            log.info("Rebuilt search index index={} documents={} replaced={}",
                    index, documents.size(), replaced);
        } catch (IOException e) {
            throw new SearchUnavailableException("Could not rebuild the search index", e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return client.ping().value();
        } catch (IOException | RuntimeException e) {
            // Deliberately not rethrown. The whole question this answers is "should the caller
            // fall back", and an exception is one of the answers.
            log.warn("Search cluster did not answer a ping: {}", e.toString());
            return false;
        }
    }

    private void send(BulkRequest.Builder bulk, int size, String what) {
        try {
            // wait_for rather than the 1s default refresh. Without it a write is not visible to
            // the next search, which reads as a lost update in a test and as a stale listing
            // for a second in production - and a second is long enough for somebody who just
            // published to reload and not see their own event.
            var response = client.bulk(bulk.refresh(co.elastic.clients.elasticsearch._types
                    .Refresh.WaitFor).build());
            if (response.errors()) {
                // Logged per item rather than as "the bulk failed": one bad document in fifty
                // is a different problem from a cluster refusing everything, and the difference
                // is invisible in an aggregate.
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .forEach(item -> log.error("Search {} failed id={} reason={}",
                                what, item.id(), item.error().reason()));
                throw new SearchUnavailableException(
                        "The search cluster refused part of a bulk request", null);
            }
            log.debug("Search {} count={}", what, size);
        } catch (IOException e) {
            throw new SearchUnavailableException("Could not reach the search cluster", e);
        }
    }

    private void create(String index) throws IOException {
        client.indices().create(create -> create
                .index(index)
                .settings(settings())
                .mappings(mapping()));
    }

    private static IndexSettings settings() {
        return IndexSettings.of(settings -> settings
                // One node, so a replica has nowhere to go and the index would sit yellow
                // forever waiting for one. Stated rather than inherited, because "yellow" then
                // means something is actually wrong.
                .numberOfShards("1")
                .numberOfReplicas("0")
                .analysis(IndexSettingsAnalysis.of(analysis -> analysis
                        .analyzer(ANALYZER, analyzer -> analyzer.custom(custom -> custom
                                .tokenizer("standard")
                                .filter("lowercase", "asciifolding"))))));
    }

    private static TypeMapping mapping() {
        return TypeMapping.of(mapping -> mapping
                .properties("title", text())
                // Not a keyword sub-field on every text field, only where one is needed. A
                // keyword copy of a description is a 5,000-character term nothing will ever
                // match, indexed on every write.
                .properties("description", text())
                .properties("organizationName", text())
                .properties("venueName", text())
                // Slugs are matched exactly and aggregated on, never analysed: `tp-ho-chi-minh`
                // put through a tokenizer becomes four terms and stops being one city.
                .properties("citySlug", Property.of(property -> property.keyword(keyword -> keyword)))
                .properties("categorySlug", Property.of(property -> property.keyword(keyword -> keyword)))
                .properties("startsAt", Property.of(property -> property.date(date -> date)))
                .properties("priceFrom", Property.of(property -> property.long_(number -> number)))
                // Carried so a card can be drawn from a hit, and indexed as keyword because
                // nobody searches a URL.
                .properties("coverImageUrl", indexless())
                .properties("coverImageAlt", indexless())
                .properties("coverImageSizes", Property.of(property -> property
                        .object(object -> object.enabled(false)))));
    }

    /**
     * A text field, analysed for folding.
     *
     * <p>No boost here. A mapping-time boost is applied at index time and baked into the
     * stored norms, so changing which field matters most would mean reindexing; the same
     * weighting expressed in the query is a line of code and takes effect on the next request.
     * Elasticsearch deprecated the mapping form for exactly this reason.
     */
    private static Property text() {
        return Property.of(property -> property.text(text -> text.analyzer(ANALYZER)));
    }

    /** Stored and returned, never searched. */
    private static Property indexless() {
        return Property.of(property -> property.keyword(keyword -> keyword.index(false)));
    }

    private static String newIndexName() {
        return ALIAS + "-" + Instant.now().toEpochMilli();
    }
}
