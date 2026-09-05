package com.eventticket.checkout.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>The work is a {@code SECURITY DEFINER} function because a scheduler has no tenant at all:
 * no organization and no user with which to satisfy a policy.
 */
@Component
public class ExpireLapsedOrders {

    private static final Logger log = LoggerFactory.getLogger(ExpireLapsedOrders.class);

    private final JdbcTemplate jdbc;

    public ExpireLapsedOrders(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${app.checkout.expiry-sweep:PT1M}")
    @Transactional
    public void sweep() {
        Integer expired = jdbc.queryForObject("select expire_lapsed_orders()", Integer.class);
        if (expired != null && expired > 0) {
            log.info("Expired {} orders whose holds had lapsed", expired);
        }
    }
}
