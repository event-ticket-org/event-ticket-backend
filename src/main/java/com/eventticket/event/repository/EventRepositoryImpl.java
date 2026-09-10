package com.eventticket.event.repository;

import com.eventticket.event.domain.Event;
import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.mongo.TextFolding;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class EventRepositoryImpl implements EventQueries {

    private final MongoTemplate mongo;

    public EventRepositoryImpl(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Event> findPage(UUID organizationId, Collection<Event.Status> statuses,
                                Instant cursorAt, UUID cursorId, int limit) {
        Query query = new Query(Criteria.where("organizationId").is(organizationId)
                .and("status").in(statuses)
                .orOperator(Criteria.where("createdAt").lt(cursorAt),
                        Criteria.where("createdAt").is(cursorAt).and("_id").lt(cursorId)))
                .with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"))
                .limit(limit);
        return mongo.find(query, Event.class);
    }

    /**
     * requirements/009. The listing, and the clearest application-side join in the system.
     *
     * <p>The SQL ended {@code and e.organization_id in (select o.id from organization o where
     * o.status = 'APPROVED')}, and the planner decided how to run it - as a semi-join, a hash,
     * whatever the statistics suggested - inside one statement against one snapshot.
     *
     * <p><strong>MongoDB cannot express that in a query at all.</strong> The two options are
     * {@code $lookup}, which is a left outer join executed per input document and belongs in
     * reporting rather than on a request path, or doing it here: fetch the approved ids, then
     * {@code $in} them. That is the application-side join the design document argues for, and
     * this is what it costs -
     *
     * <ul>
     *   <li><strong>A second round trip</strong>, where Postgres had one statement.</li>
     *   <li><strong>An unbounded intermediate list.</strong> Every approved Organization's id
     *       is pulled into the JVM and sent back in the filter. At this platform's scale that
     *       is small; it is still a list whose size the query author does not control, and the
     *       shape does not survive growth.</li>
     *   <li><strong>Two snapshots.</strong> An Organization approved between the two calls is
     *       seen by one and not the other. Postgres read both from one snapshot. The window is
     *       tiny and the consequence here is trivial - an event appears a listing later - but
     *       "the join is no longer atomic with the query" is true generally and will not always
     *       be trivial.</li>
     * </ul>
     */
    @Override
    public List<Event> findPublicPage(Instant now, Event.Status published,
                                      Organization.Status approved, List<UUID> venueIds,
                                      Instant startsAfter, Instant startsBefore, String title,
                                      Instant cursorAt, UUID cursorId, int limit) {
        List<UUID> approvedOrganizations = mongo.findDistinct(
                new Query(Criteria.where("status").is(approved.name())),
                "_id", "organization", UUID.class);
        if (approvedOrganizations.isEmpty()) {
            return List.of();
        }

        Criteria criteria = Criteria.where("status").is(published.name())
                .and("listed").is(true)
                .and("startsAt").gt(now).gte(startsAfter).lte(startsBefore)
                .and("organizationId").in(approvedOrganizations);
        if (venueIds != null) {
            criteria = criteria.and("venueId").in(venueIds);
        }
        if (title != null && !title.isBlank()) {
            // Against the folded copy, not the title. $regex ignores collation, so the folding
            // has to have happened before the query - on the document when it was written, and
            // on the search term here, by the same method so the two cannot disagree.
            criteria = criteria.and("titleFolded")
                    .regex(Pattern.quote(TextFolding.fold(title.strip())));
        }
        criteria = criteria.orOperator(
                Criteria.where("startsAt").gt(cursorAt),
                Criteria.where("startsAt").is(cursorAt).and("_id").gt(cursorId));

        return mongo.find(new Query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "startsAt", "_id"))
                .limit(limit), Event.class);
    }

    /**
     * How many seats each Event has sold, and how much money it is holding.
     *
     * <p><strong>This was a common table expression and is now a single {@code $group}.</strong>
     * The CTE existed for one reason: {@code ticket_order} had to be joined to
     * {@code order_seat} to count seats, and an aggregate over that join counts the join rather
     * than the thing - {@code sum(total_amount)} multiplied every Order by its seat count and
     * reported 3,250,000 where the answer was 1,250,000, in the field an organizer checks
     * first. Aggregating the Orders in a CTE first was the fix.
     *
     * <p>The seats are inside the Order now, so {@code $size: "$seats"} counts them without a
     * join, and there is nothing left to multiply. The bug was not fixed by this migration; the
     * conditions under which it can be written were removed by the embedding decision.
     *
     * <p>A REFUNDED Order still counts as neither sold nor held, for the reasons it did before:
     * its seats went back on sale, and its money went back to the buyer.
     */
    @Override
    public List<EventCounts> countsFor(Collection<UUID> eventIds) {
        return mongo.aggregate(countsPipeline(eventIds), "ticketOrder", EventCounts.class)
                .getMappedResults();
    }

    /**
     * Written as raw stages rather than through the aggregation DSL, which cannot put
     * {@code $size} inside {@code $cond} without more ceremony than the pipeline itself.
     */
    private static Aggregation countsPipeline(Collection<UUID> eventIds) {
        return Aggregation.newAggregation(
                Aggregation.match(Criteria.where("eventId").in(eventIds)),
                context -> new org.bson.Document("$group", new org.bson.Document()
                        .append("_id", "$eventId")
                        .append("sold", sumWhenPaid(new org.bson.Document("$size", "$seats")))
                        .append("salesAmount", sumWhenPaid("$totalAmount"))
                        .append("refundRequired", new org.bson.Document("$sum",
                                new org.bson.Document("$cond", List.of("$refundRequired", 1, 0))))
                        .append("salesCurrency", new org.bson.Document("$max",
                                new org.bson.Document("$cond", List.of(
                                        new org.bson.Document("$eq", List.of("$status", "PAID")),
                                        "$currency", null))))),
                context -> new org.bson.Document("$project", new org.bson.Document()
                        .append("_id", 0)
                        .append("eventId", "$_id")
                        .append("sold", 1)
                        .append("salesAmount", 1)
                        .append("refundRequired", 1)
                        .append("salesCurrency", 1)));
    }

    private static org.bson.Document sumWhenPaid(Object value) {
        return new org.bson.Document("$sum", new org.bson.Document("$cond", List.of(
                new org.bson.Document("$eq", List.of("$status", "PAID")), value, 0)));
    }
}
