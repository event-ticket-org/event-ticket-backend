package com.eventticket.shared.mongo;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The constraints and indexes, as a migration rather than as annotations.
 *
 * <h2>Why this is not {@code auto-index-creation}</h2>
 *
 * <p>Spring Data can build indexes from {@code @Indexed} annotations when the context starts,
 * and it is the wrong mechanism for the same reason {@code ddl-auto: validate} was: an index
 * that appears because a class was annotated is an index nobody reviewed, created at a moment
 * nobody chose, against a collection that may hold millions of documents.
 *
 * <h2>This is not Mongock either, and that is worth reporting</h2>
 *
 * <p>Mongock is the Flyway-shaped answer - ordered, recorded, applied once, with a history
 * collection that can answer "has this run". It was the intended mechanism here and was
 * abandoned after it cost an hour. Its jar registers no Spring autoconfiguration at all, so it
 * needs an explicit {@code @EnableMongock}; and with that added, {@code mongock.migration-scan-package}
 * did not bind under Spring Boot 4 - the runner started and then failed with "Scan package for
 * changeLogs is not set". The Flyway equivalent is not ready for this stack yet.
 *
 * <p>What is left is weaker in one specific way and it should be named rather than glossed:
 * <strong>there is no history.</strong> This cannot answer "has index X been applied", only
 * "ensure it is". {@code createIndex} is idempotent for an identical specification - and errors
 * on a <em>changed</em> one under the same name, which is a crude but real guard against a
 * silent redefinition. For a production system the history matters and Mongock, or something
 * like it, would have to be made to work.
 *
 * <h2>What Postgres enforced that this restores, and what it does not</h2>
 *
 * <p><strong>Plain unique indexes port exactly.</strong> {@code payment_event (provider,
 * provider_event_id)} - the webhook idempotency key that stops a redelivered payment being
 * applied twice - is a unique index and means precisely the same thing.
 *
 * <p><strong>Partial unique indexes port, but a negation in one does not.</strong> This was
 * written up as a straight win before it was tried, and it is not: a
 * {@code partialFilterExpression} admits {@code $eq}, {@code $exists}, the range operators,
 * {@code $type}, {@code $and}, {@code $or} and {@code $in} - and rejects {@code $ne}. Both of
 * this system's partial indexes were negations, so both had to become enumerations of every
 * other value. See the comment at the index itself: the rewrite is correct today and goes
 * quietly wrong the day a status is added.
 *
 * <p><strong>Case-insensitive uniqueness needs the index and the query to agree.</strong>
 * {@code app_user_email_key ON app_user (lower(email))} becomes a unique index with a collation,
 * and a query that does not ask for the same collation will neither use it nor be constrained by
 * it. Two halves that must match, where Postgres had one expression.
 *
 * <p><strong>CHECK constraints are not here and mostly could be.</strong> {@code $jsonSchema}
 * validators express type, enum and range, which covers almost all twenty of them. They are left
 * out deliberately: a validator cannot see a document's previous value, so the three that
 * actually mattered - the immutability triggers - are inexpressible either way, and adding
 * validators for the easy ones would make the collection look better defended than it is.
 *
 * <p><strong>TTL indexes are a genuine gain.</strong> Postgres needed a sweeper to expire
 * verification and reset tokens; MongoDB deletes them itself. Note it deletes rather than marks,
 * so a consumed-token record disappears instead of persisting as evidence - which is why the
 * TTL below is generous rather than exact.
 */
@Component
public class MongoIndexes {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexes.class);

    private final MongoTemplate mongo;

    public MongoIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @PostConstruct
    public void ensureIndexes() {
        createIndexes(mongo.getDb());
        log.info("Ensured MongoDB indexes and unique constraints");
    }

    private static final Collation CASE_INSENSITIVE = Collation.builder()
            .locale("en").collationStrength(CollationStrength.SECONDARY).build();

    private void createIndexes(MongoDatabase database) {
        // --- uniqueness that used to be a constraint ------------------------------------
        database.getCollection("appUser").createIndex(Indexes.ascending("email"),
                new IndexOptions().unique(true).collation(CASE_INSENSITIVE).name("appUser_email"));

        database.getCollection("membership").createIndex(
                Indexes.ascending("organizationId", "userId"),
                new IndexOptions().unique(true).name("membership_unique_per_org"));

        database.getCollection("paymentEvent").createIndex(
                Indexes.ascending("provider", "providerEventId"),
                new IndexOptions().unique(true).name("paymentEvent_idempotency"));

        database.getCollection("paymentSession").createIndex(
                Indexes.ascending("provider", "providerRef"),
                new IndexOptions().unique(true).name("paymentSession_providerRef")
                        .partialFilterExpression(new Document("providerRef",
                                new Document("$exists", true))));

        // refund_provider_ref_unique, and the index this migration would have got wrong.
        //
        // V8 dropped NOT NULL from refund.provider_ref precisely so that many refused refunds -
        // which never reached a provider and so have no reference - could coexist. Postgres
        // allowed that for free, because its UNIQUE treats NULLs as distinct. Ported literally,
        // this index would permit exactly one refused refund in the entire system and reject
        // every one after it, and the failure would arrive at the worst moment: on the second
        // refund that a provider declined.
        database.getCollection("refund").createIndex(
                Indexes.ascending("provider", "providerRef"),
                new IndexOptions().unique(true).name("refund_providerRef")
                        .partialFilterExpression(new Document("providerRef",
                                new Document("$exists", true))));

        // Partial, and not for the reason Postgres's version was.
        //
        // A Postgres UNIQUE constraint treats NULLs as distinct, so any number of rows may
        // leave the column empty. A MongoDB unique index does the opposite: a missing field is
        // indexed as null, and the second document without one is a duplicate key error. That
        // difference is silent, it only appears on the second write, and it turns "this column
        // is optional" into "at most one document may omit it".
        //
        // Restricting the index to documents that have the field restores the Postgres
        // behaviour. Every ticket has a codeLookup, so this changes nothing today - it is here
        // so that the index does not have an opinion about documents it was never meant to
        // constrain.
        database.getCollection("ticket").createIndex(Indexes.ascending("codeLookup"),
                new IndexOptions().unique(true).name("ticket_code_lookup")
                        .partialFilterExpression(new Document("codeLookup",
                                new Document("$exists", true))));

        database.getCollection("eventSeat").createIndex(Indexes.ascending("eventId", "label"),
                new IndexOptions().unique(true).name("eventSeat_label_unique"));

        // --- the partial unique indexes, and the one place a negation could not survive ----
        //
        // Postgres wrote these as `WHERE status <> 'VOID'` and `WHERE status <> 'REFUND_FAILED'`.
        // A partialFilterExpression cannot say that: MongoDB restricts it to $eq, $exists,
        // the range operators, $type, $and, $or and $in, and rejects $ne outright with
        // "Expression not supported in partial index: $not".
        //
        // So the exclusion becomes an enumeration of everything else, and that is NOT the same
        // statement. `<> 'VOID'` adapts to a new status automatically and correctly; the $in
        // below silently stops covering one. Add a ticket status tomorrow and this index quietly
        // narrows - no error, no failing test, just a uniqueness rule that no longer applies to
        // the new state. The comment is the only thing linking the two, which is exactly the
        // kind of coupling the database used to hold for us.
        database.getCollection("ticket").createIndex(Indexes.ascending("eventSeatId"),
                new IndexOptions().unique(true).name("ticket_one_per_seat")
                        .partialFilterExpression(new Document("status",
                                new Document("$in", List.of("VALID", "REDEEMED")))));

        database.getCollection("refund").createIndex(Indexes.ascending("orderId"),
                new IndexOptions().unique(true).name("refund_one_live_per_order")
                        .partialFilterExpression(new Document("status",
                                new Document("$in", List.of("REFUND_PENDING", "REFUNDED")))));

        // --- reads -----------------------------------------------------------------------
        database.getCollection("event").createIndex(Indexes.ascending("startsAt", "_id"),
                new IndexOptions().name("event_public")
                        .partialFilterExpression(new Document("listed", true)));
        database.getCollection("event").createIndex(
                Indexes.compoundIndex(Indexes.ascending("organizationId"),
                        Indexes.descending("createdAt", "_id")),
                new IndexOptions().name("event_org"));
        database.getCollection("eventSeat").createIndex(Indexes.ascending("eventId"),
                new IndexOptions().name("eventSeat_event"));
        database.getCollection("eventSeat").createIndex(Indexes.ascending("heldByOrderId"),
                new IndexOptions().name("eventSeat_hold"));
        database.getCollection("ticketOrder").createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerUserId"),
                        Indexes.descending("createdAt", "_id")),
                new IndexOptions().name("ticketOrder_buyer"));
        database.getCollection("ticketOrder").createIndex(Indexes.ascending("eventId"),
                new IndexOptions().name("ticketOrder_event"));
        database.getCollection("venue").createIndex(Indexes.ascending("city"),
                new IndexOptions().collation(CASE_INSENSITIVE).name("venue_city"));
        database.getCollection("auditEntry").createIndex(
                Indexes.compoundIndex(Indexes.ascending("organizationId"),
                        Indexes.descending("occurredAt")),
                new IndexOptions().name("auditEntry_org"));
        database.getCollection("scan").createIndex(
                Indexes.compoundIndex(Indexes.ascending("eventId"),
                        Indexes.descending("occurredAt")),
                new IndexOptions().name("scan_event"));
        database.getCollection("emailDelivery").createIndex(Indexes.ascending("nextAttemptAt"),
                new IndexOptions().name("emailDelivery_pending")
                        .partialFilterExpression(new Document("status", "PENDING")));

        // --- TTL, which Postgres had no answer for ---------------------------------------
        // Generous rather than exact: these delete rather than mark, so a token disappears
        // instead of remaining as evidence that it was used. The application still decides
        // whether a token is usable; this only stops the collections growing forever.
        database.getCollection("emailVerificationToken").createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().name("verification_ttl")
                        .expireAfter(7L, TimeUnit.DAYS));
        database.getCollection("passwordResetToken").createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().name("reset_ttl").expireAfter(7L, TimeUnit.DAYS));
        database.getCollection("refreshToken").createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().name("refresh_ttl").expireAfter(30L, TimeUnit.DAYS));
    }

}
