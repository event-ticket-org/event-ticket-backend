package com.eventticket.event.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import java.net.URI;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the index, and only when there is one to wire.
 *
 * <p>{@code app.search.uri} absent means there is no search cluster at all, the same shape
 * {@code STRIPE_SECRET_KEY} and {@code MAIL_HOST} use: a fresh clone and the suite's
 * non-search tests run with no cluster, and the listing serves from Postgres. Empty counts as
 * absent - a compose file that declares the variable with nothing behind it hands the
 * application an empty string, which is the trap {@code MAIL_HOST} documents.
 *
 * <p><strong>There is no no-op fallback implementation.</strong> A port with a silent stand-in
 * is how {@code LoggingEmailTransport} ended up being the only mail transport in every
 * deployment, marking every message SENT while nobody could verify an address. A missing bean
 * is visible; a stand-in that swallows everything is not.
 */
@Configuration
@ConditionalOnProperty(name = "app.search.uri")
public class SearchIndexConfiguration {

    @Bean
    public ElasticsearchClient elasticsearchClient(@Value("${app.search.uri}") String uri) {
        URI parsed = URI.create(uri);
        var rest = co.elastic.clients.transport.rest5_client.low_level.Rest5Client
                .builder(new HttpHost(parsed.getScheme(), parsed.getHost(), parsed.getPort()))
                .build();
        return new ElasticsearchClient(new Rest5ClientTransport(rest, jsonMapper()));
    }

    /**
     * The client's own mapper, configured because nothing else configures it.
     *
     * <p>This application serialises with Jackson 3; the Elasticsearch client ships a mapper on
     * Jackson 2 and uses it for request bodies, so an {@code Instant} in a document goes through
     * a mapper Spring never sees. Left alone it fails at the first indexing call with "Java 8
     * date/time type not supported by default" - which reads like a misconfiguration here and is
     * a module missing there.
     *
     * <p>ISO-8601 rather than epoch milliseconds. Both are accepted by a {@code date} field, and
     * only one of them can be read by somebody looking at a document.
     */
    private static JacksonJsonpMapper jsonMapper() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature
                        .WRITE_DATES_AS_TIMESTAMPS);
        return new JacksonJsonpMapper(mapper);
    }

    @Bean
    public EventSearchIndex eventSearchIndex(ElasticsearchClient client) {
        return new ElasticsearchEventIndex(client);
    }

    /**
     * The clock, separate from the work and switched off in the suite - see
     * {@link com.eventticket.event.search.outbox.SearchIndexSchedule} for the deadlock that
     * separation exists for.
     */
    @Bean
    @ConditionalOnProperty(name = "app.search.scheduled", havingValue = "true",
            matchIfMissing = true)
    public com.eventticket.event.search.outbox.SearchIndexSchedule searchIndexSchedule(
            com.eventticket.event.search.outbox.IndexPendingEvents incremental,
            com.eventticket.event.search.outbox.RebuildSearchIndex rebuild) {
        return new com.eventticket.event.search.outbox.SearchIndexSchedule(incremental, rebuild);
    }
}
