package com.eventticket.shared.mongo;

import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tells the driver how to write a UUID. Without it, this application cannot write anything.
 *
 * <p>Postgres has a native {@code uuid} type. One representation, no ambiguity, and
 * {@code id uuid} says everything there is to say about it.
 *
 * <p>MongoDB stores a UUID as BSON Binary with a subtype, and there are four in circulation:
 * the modern standard (subtype 4) and three mutually incompatible legacy byte orders left
 * behind by the old Java, C# and Python drivers. A UUID written under one and read under
 * another comes back as a <em>different UUID</em>, with no error. Faced with that, the driver
 * refuses to encode a UUID at all until it is told which to use - which is the right call, and
 * a class of problem that simply does not exist in Postgres.
 *
 * <p>It is set here as a client setting rather than as {@code spring.data.mongodb.uuid-representation},
 * because that property is not what configures the client Testcontainers' service connection
 * builds - the property was set, appeared to be honoured, and every write still failed with
 * {@code The uuidRepresentation has not been specified}.
 *
 * <p>Every primary key in this system is a UUID, so the failure was total and immediate: 148 of
 * 185 tests, all reporting a 500 from registration. That is the good version of this bug. The
 * bad version is choosing a legacy representation, or changing it later, and finding that
 * documents written before the change can no longer be found by their own ids.
 */
@Configuration
public class MongoUuidConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer uuidRepresentation() {
        return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
    }
}
