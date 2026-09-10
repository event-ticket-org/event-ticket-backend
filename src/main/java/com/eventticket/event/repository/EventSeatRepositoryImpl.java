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
        Criteria free = new Criteria().andOperator(
                Criteria.where("soldAt").is(null),
                new Criteria().orOperator(
                        Criteria.where("heldUntil").is(null),
                        Criteria.where("heldUntil").lte(now)));

        Aggregation pipeline = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("eventId").in(eventIds).and("forSale").is(true)),
                Aggregation.group("eventId")
                        .sum(ConditionalOperators.when(free).then(1).otherwise(0)).as("available")
                        .count().as("total"),
                Aggregation.project("available", "total").and("_id").as("eventId"));

        return mongo.aggregate(pipeline, EventSeat.class, SeatCounts.class).getMappedResults();
    }
}
