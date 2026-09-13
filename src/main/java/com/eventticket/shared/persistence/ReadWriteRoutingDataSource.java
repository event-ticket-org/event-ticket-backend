package com.eventticket.shared.persistence;

import com.eventticket.shared.tenancy.TenantContext;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Sends each transaction to the primary or to the replica.
 *
 * <h2>The rule</h2>
 *
 * <pre>
 *   not read-only                        -> primary   (it writes)
 *   read-only, user has written recently -> primary    unless the replica has caught up
 *   read-only, no recent write           -> replica
 * </pre>
 *
 * <h2>Why not simply "read-only goes to the replica"</h2>
 *
 * <p>Because {@code readOnly} does not mean what routing needs it to mean. It asserts *this
 * transaction issues no writes*; a replica asks *is stale data safe here*. Eighteen use cases in
 * this application carry {@code @Transactional(readOnly = true)} and thirteen of them are
 * authenticated screens read moments after the same person changed what they display -
 * {@code GetOrder} is opened by the browser the instant {@code POST /checkout} returns, and a
 * lagging replica answers 404 for an order that was just created.
 *
 * <p>The alternative to this class was classifying those eighteen by hand. That was rejected
 * because the classification has to be re-made by every future developer, and because it had
 * already started to slip: {@code docs/replication/02-this-project.md} calls three of them "mild
 * risk, acceptable", which is not a standard that survives contact with a new endpoint.
 *
 * <p>Here, {@code @Transactional(readOnly = true)} is sufficient and safe on its own. A new read
 * endpoint inherits the guarantee without its author knowing this class exists.
 *
 * <h2>What it does not cover</h2>
 *
 * <p>Your own writes, not other people's. A read that must see something *the system* wrote
 * moments ago - a progress poll during a cancellation, say - is not protected, because the
 * reader has no write of their own to wait for. Those are marked to use the primary explicitly;
 * there is one in this codebase.
 */
class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    enum Target { PRIMARY, REPLICA }

    private final ReplicaFreshness freshness;

    ReadWriteRoutingDataSource(DataSource primary, DataSource replica, ReplicaFreshness freshness) {
        this.freshness = freshness;
        setTargetDataSources(Map.of(Target.PRIMARY, primary, Target.REPLICA, replica));
        setDefaultTargetDataSource(primary);
        // Called here because this object is built by hand inside a @Bean method rather than
        // being a bean itself, so nothing else will call it. AbstractRoutingDataSource resolves
        // its target map in afterPropertiesSet, and without it every lookup fails with
        // "DataSource router not initialized" - at startup, when Flyway asks for the first
        // connection. It is not a bean on purpose: a second DataSource bean would break
        // Boot's @ConditionalOnSingleCandidate wiring for JdbcTemplate.
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        // RoutingContext, not TransactionSynchronizationManager - Spring publishes its
        // read-only flag in prepareSynchronization, which runs after doBegin has already taken
        // the connection. See RoutingContext for how that was found.
        if (!RoutingContext.isReadOnly()) {
            return Target.PRIMARY;
        }
        if (PrimaryOnly.isRequired()) {
            return Target.PRIMARY;
        }
        return freshness.isSafeFor(TenantContext.userId()) ? Target.REPLICA : Target.PRIMARY;
    }
}
