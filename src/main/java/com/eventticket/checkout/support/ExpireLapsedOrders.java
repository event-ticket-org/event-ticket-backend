package com.eventticket.checkout.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.eventticket.checkout.domain.Order;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping, and worth being clear that it is only housekeeping.
 *
 * <p>The seats of a lapsed Order are already free the instant their {@code held_until} passes
 * - nothing here releases them - and a payment attempt against a lapsed Order is refused by
 * the Order itself. This exists so that an Order the buyer walked away from stops describing
 * itself as "awaiting payment" in their own list, and so that {@code EXPIRED}, which the
 * contract publishes, is a status the system actually produces.
 *
 * <p>The work was a {@code SECURITY DEFINER} function because a scheduler has no tenant at
 * all - no organization and no user with which to satisfy a policy - and so could not have
 * touched {@code ticket_order} without one.
 *
 * <p>It is an ordinary update now. Not because the problem was solved, but because the policy
 * that created it is gone: <strong>a caller with no tenant is no longer refused anything.</strong>
 * The privileged function and the thing it was privileged against disappeared together, and it
 * would be easy to read the shorter code as a simplification. It is the absence of a control.
 */
@Component
public class ExpireLapsedOrders {

    private static final Logger log = LoggerFactory.getLogger(ExpireLapsedOrders.class);

    private final MongoTemplate mongo;

    public ExpireLapsedOrders(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Scheduled(fixedDelayString = "${app.checkout.expiry-sweep:PT1M}")
    @Transactional
    public void sweep() {
        long expired = mongo.updateMulti(
                new Query(Criteria.where("status").is(Order.Status.AWAITING_PAYMENT.name())
                        .and("holdExpiresAt").lte(Instant.now())),
                new Update().set("status", Order.Status.EXPIRED.name()),
                Order.class).getModifiedCount();
        if (expired > 0) {
            log.info("Expired {} orders whose holds had lapsed", expired);
        }
    }
}
