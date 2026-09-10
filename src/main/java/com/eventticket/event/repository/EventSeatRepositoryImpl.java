package com.eventticket.event.repository;

import com.eventticket.event.domain.EventSeat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Named {@code EventSeatRepositoryImpl} because Spring Data finds a fragment by that convention
 * and by nothing else - rename this class and the methods silently stop being implemented.
 */
public class EventSeatRepositoryImpl implements EventSeatQueries {

    private final MongoTemplate mongo;

    public EventSeatRepositoryImpl(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * {@code select distinct} becomes MongoDB's own {@code distinct} command, which returns the
     * values and not the documents holding them. Worth saying because the obvious translation -
     * fetch the seats, collect the names in Java - reads two thousand documents to learn six
     * strings.
     */
    @Override
    public List<String> findTierNames(UUID eventId) {
        return mongo.findDistinct(new Query(Criteria.where("eventId").is(eventId)),
                "tierName", EventSeat.class, String.class);
    }

    /**
     * {@code GROUP BY} with a conditional sum, which is an aggregation pipeline here.
     *
     * <p>The translation is close enough to read side by side: {@code where} is {@code $match},
     * {@code group by} is {@code $group}, and {@code sum(case when … then 1 else 0 end)} is a
     * {@code $sum} over {@code $cond}. What does not survive is the type: the SQL returned a
     * constructor expression, so the shape was checked at startup, where a pipeline is checked
     * when it runs.
     *
     * <p>The conditions are still {@link EventSeat#availability()} written twice, and still
     * have to stay in step - a listing that disagreed with the seat map it links to would be
     * worse than one that said nothing. That risk is unchanged by the migration; it is simply
     * expressed in a different language now.
     */
    @Override
    public List<SeatCounts> countSeats(List<UUID> eventIds, Instant now) {
        // Availability, written so that a missing field and an explicit null mean the same
        // thing.
        //
        // The first version used Spring Data's ConditionalOperators with a Criteria, which
        // produced { $eq: ["$soldAt", null] }. A seat that has never been sold has no soldAt
        // field at all - Spring Data omits nulls when it writes - and that comparison did not
        // do what it looks like it does: every seat counted as unavailable, the listing said
        // "0 of 6 left", and nothing failed. The total beside it was right, which is what made
        // it look like a mapping problem rather than a logic one.
        //
        // $not is the reliable form: it is true for missing, null and false alike, so it asks
        // "is this seat unsold" without depending on whether the field was written.
        org.bson.Document unsold = new org.bson.Document("$not", List.of("$soldAt"));
        org.bson.Document unheld = new org.bson.Document("$or", List.of(
                new org.bson.Document("$not", List.of("$heldUntil")),
                new org.bson.Document("$lte", List.of("$heldUntil", now))));

        Aggregation pipeline = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("eventId").in(eventIds).and("forSale").is(true)),
                context -> new org.bson.Document("$group", new org.bson.Document()
                        .append("_id", "$eventId")
                        .append("available", new org.bson.Document("$sum",
                                new org.bson.Document("$cond", List.of(
                                        new org.bson.Document("$and", List.of(unsold, unheld)),
                                        1, 0))))
                        .append("total", new org.bson.Document("$sum", 1))),
                context -> new org.bson.Document("$project", new org.bson.Document()
                        .append("_id", 0)
                        .append("eventId", "$_id")
                        .append("available", 1)
                        .append("total", 1)));

        return mongo.aggregate(pipeline, EventSeat.class, org.bson.Document.class)
                .getMappedResults().stream()
                .map(d -> new SeatCounts(
                        d.get("eventId", UUID.class),
                        ((Number) d.getOrDefault("available", 0)).longValue(),
                        ((Number) d.getOrDefault("total", 0)).longValue()))
                .toList();
    }
}
