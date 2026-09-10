package com.eventticket.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventticket.support.ApiTest;
import com.mongodb.MongoWriteException;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The constraints Postgres used to enforce, and whether MongoDB actually enforces them.
 *
 * <p>Written because {@code 02-comparison.md} claimed partial unique indexes "port one to one",
 * and a claim in a document is worth nothing. It was wrong - {@code partialFilterExpression}
 * rejects {@code $ne}, so both of this system's partial indexes had to become enumerations - and
 * writing these tests is what found that. They write the duplicate and watch the database refuse
 * it, which is the standard the Postgres suite held itself to: {@code PublishedSeatMapIsFrozenTest}
 * issued the forbidden statement rather than trusting the trigger to be installed.
 *
 * <p>They also prove {@code MongoIndexes} ran at all. An index migration that silently does not
 * execute leaves an application that works perfectly until two things happen at once.
 */
class MongoIndexesTest extends ApiTest {

    /**
     * The webhook idempotency key. A provider that does not hear a 2xx redelivers, so this
     * index is what stops one payment being applied twice - and it is one of the few guarantees
     * that survives the migration exactly, because it is a plain unique index over a collection.
     */
    @Test
    @DisplayName("a redelivered webhook cannot be recorded twice")
    void paymentEventIdempotencyIsEnforced() {
        Document first = new Document("_id", UUID.randomUUID())
                .append("provider", "STRIPE").append("providerEventId", "evt_repeated");
        mongo.getCollection("paymentEvent").insertOne(first);

        Document redelivery = new Document("_id", UUID.randomUUID())
                .append("provider", "STRIPE").append("providerEventId", "evt_repeated");

        assertThatThrownBy(() -> mongo.getCollection("paymentEvent").insertOne(redelivery))
                .isInstanceOf(MongoWriteException.class)
                .hasMessageContaining("duplicate key");
    }

    /**
     * {@code ticket_one_per_seat ON ticket (event_seat_id) WHERE status <> 'VOID'}.
     *
     * <p>The partial filter is the whole point: a seat may have many VOID tickets - cancelling
     * an event voids them and the seat goes back on sale - and exactly one that is not.
     *
     * <p>It is an approximation rather than a port. MongoDB will not accept {@code $ne} in a
     * partial filter, so the exclusion is written as {@code $in} over every other status, and
     * this test passes only for as long as that list is complete.
     */
    @Test
    @DisplayName("one live ticket per seat, and any number of void ones")
    void ticketOnePerSeatIsPartial() {
        UUID seat = UUID.randomUUID();

        mongo.getCollection("ticket").insertOne(ticket(seat, "VALID"));

        assertThatThrownBy(() -> mongo.getCollection("ticket").insertOne(ticket(seat, "VALID")))
                .isInstanceOf(MongoWriteException.class);

        // ...but the filter excludes VOID, so a seat can carry any number of those. Without the
        // partialFilterExpression this line fails, and a cancelled event could never resell a
        // seat.
        assertThatCode(() -> {
            mongo.getCollection("ticket").insertOne(ticket(seat, "VOID"));
            mongo.getCollection("ticket").insertOne(ticket(seat, "VOID"));
        }).doesNotThrowAnyException();
    }

    /**
     * {@code app_user_email_key ON app_user (lower(email))} - and the half that is easy to miss.
     *
     * <p>Postgres folded case in the index expression, so the constraint held however the query
     * was written. Here the folding is a collation on the index, and <strong>a query that does
     * not ask for the same collation neither uses the index nor is constrained by it</strong>.
     * One expression became two halves that have to agree.
     */
    @Test
    @DisplayName("email uniqueness is case-insensitive, through a collation on the index")
    void emailUniquenessFoldsCase() {
        String address = "Case" + UUID.randomUUID() + "@Example.com";
        mongo.getCollection("appUser").insertOne(
                new Document("_id", UUID.randomUUID()).append("email", address));

        assertThatThrownBy(() -> mongo.getCollection("appUser").insertOne(
                new Document("_id", UUID.randomUUID())
                        .append("email", address.toLowerCase(java.util.Locale.ROOT))))
                .isInstanceOf(MongoWriteException.class)
                .hasMessageContaining("duplicate key");
    }

    /** Proves the migration ran, rather than inferring it from the application starting. */
    @Test
    @DisplayName("the index migration actually ran")
    void indexesExist() {
        List<String> ticketIndexes = names("ticket");
        assertThat(ticketIndexes).contains("ticket_one_per_seat", "ticket_code_lookup");
        assertThat(names("paymentEvent")).contains("paymentEvent_idempotency");
        assertThat(names("refund")).contains("refund_one_live_per_order");
        assertThat(names("emailVerificationToken")).contains("verification_ttl");
    }

    /**
     * Every ticket gets its own codeLookup, and the first draft of this test did not - which
     * found something. A Postgres UNIQUE treats NULLs as distinct, so rows may omit the column
     * freely; a MongoDB unique index indexes a missing field as null and rejects the second
     * document that omits it. Two tickets with no code collided on an index that has nothing to
     * do with what this test is about.
     */
    private static Document ticket(UUID seat, String status) {
        return new Document("_id", UUID.randomUUID())
                .append("eventSeatId", seat)
                .append("status", status)
                .append("codeLookup", UUID.randomUUID().toString());
    }

    private List<String> names(String collection) {
        return mongo.getCollection(collection).listIndexes().into(new java.util.ArrayList<>())
                .stream().map(index -> index.getString("name")).toList();
    }
}
