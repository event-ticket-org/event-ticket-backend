package com.eventticket.shared.tenancy;

import com.eventticket.shared.persistence.freshness.LastWriteStore;
import com.eventticket.shared.persistence.routing.RoutingContext;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Publishes the current tenant into the database session as soon as a transaction begins,
 * so that the row-level security policies in {@code V2__identity_and_tenancy.sql} have
 * something to match against.
 *
 * <p>Doing this at transaction start rather than in application code is the whole point of
 * knowledge base ADR-0004: an isolation rule that has to be remembered on every query is one
 * that will eventually be forgotten on exactly one query, and application-layer filtering
 * fails silently when it is.
 *
 * <p>{@code set_config(name, value, true)} is the function form of {@code SET LOCAL}. It is
 * used rather than the statement form because {@code SET LOCAL} cannot take a bind
 * parameter, and building that string by concatenation would be an injection site in the one
 * place we can least afford one. Being transaction-local, the setting is discarded on commit
 * or rollback, so a pooled connection never carries one request's tenant into the next.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareTransactionManager.class);

    /**
     * Null unless read routing is switched on, in which case every committed write has its
     * log position recorded so the reader can be kept off a replica that has not caught up.
     */
    private final LastWriteStore lastWrites;

    public TenantAwareTransactionManager(EntityManagerFactory emf, DataSource dataSource,
                                         LastWriteStore lastWrites) {
        super(emf);
        setDataSource(dataSource);
        this.lastWrites = lastWrites;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Before super, because super acquires the connection and the router is consulted at
        // that moment. Spring's own read-only flag is not published until prepareSynchronization,
        // which runs afterwards - so without this every read-only transaction would be served by
        // the primary, silently and correctly, and the replica would never see a query.
        RoutingContext.beginTransaction(definition.isReadOnly());
        super.doBegin(transaction, definition);

        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            try (Statement role = connection.createStatement()) {
                role.execute(TenantSql.ASSUME_RUNTIME_ROLE);
            }
            try (PreparedStatement statement = connection.prepareStatement(TenantSql.SET_TENANT)) {
                statement.setString(1, TenantContext.organizationIdAsSetting());
                statement.setString(2, TenantContext.userIdAsSetting());
                statement.execute();
            }
        } catch (SQLException e) {
            throw new CannotCreateTransactionException("Could not set the tenant for this transaction", e);
        }
    }

    /**
     * Clears the routing intent once the transaction is over. On a pooled request thread a
     * leftover read-only flag would offer the next piece of work - possibly a write - to the
     * replica.
     */
    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        try {
            super.doCleanupAfterCompletion(transaction);
        } finally {
            RoutingContext.endTransaction();
        }
    }

    /**
     * Records where in the write-ahead log this user's write landed, so their next read can be
     * kept off a replica that has not replayed it yet.
     *
     * <h2>After the commit, and that is the whole of it</h2>
     *
     * <p>The tempting version asks for the position as part of the write -
     * {@code insert ... returning pg_current_wal_lsn()} - and it is wrong. That expression is
     * evaluated before the commit record is written, so it returns a position the replica may
     * already have passed, and the guard then reports "safe to read here" while the replica is
     * genuinely a row behind. It was written that way first and caught by pausing a replica and
     * watching the guard say SAFE; see {@code docs/replication/02-this-project.md}.
     *
     * <p>{@code doCommit} runs after the commit and before the connection is unbound, so the
     * connection below is the same one, still open. The statement costs one round trip per write
     * transaction, and writes are the rare operation here.
     *
     * <p>It runs as the login role rather than {@code eventticket_app}: {@code SET LOCAL ROLE} is
     * transaction-local and has already reverted by this point, which matters because
     * {@code V3__runtime_role.sql} grants that role no functions at all.
     */
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        super.doCommit(status);

        if (lastWrites == null || status.isReadOnly()) {
            return;
        }
        UUID userId = TenantContext.userId();
        if (userId == null) {
            // A scheduled sweep has no user to record against - ExpireLapsedOrders writes on
            // nobody's behalf. There is no reader waiting to see it, so there is nothing to do.
            return;
        }
        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            return;
        }
        try (Statement statement = DataSourceUtils.getConnection(dataSource).createStatement();
             ResultSet position = statement.executeQuery("select pg_current_wal_lsn()")) {
            if (position.next()) {
                lastWrites.recordWrite(userId, position.getString(1));
            }
        } catch (SQLException e) {
            // Deliberately not fatal. The transaction has committed; failing here would turn a
            // successful write into an error the caller would reasonably retry. The cost of not
            // recording is that this user may read a stale replica once - a real cost, and a
            // smaller one than double-charging somebody.
            log.warn("Could not record the write position; this user's next read may be stale", e);
        }
    }
}
