package com.eventticket.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may read a replica, and - more importantly - who may not.
 *
 * <p>Every assertion here is about the same property: <strong>every way of being unsure routes
 * to the primary.</strong> A guard that fails towards the replica would show somebody a stale
 * copy of their own work; one that fails towards the primary costs a little throughput. Only one
 * of those is a bug worth having.
 */
class ReplicaFreshnessTest {

    private final LastWriteStore writes = new InMemoryLastWriteStore();
    private static final UUID SOMEBODY = UUID.randomUUID();

    @Test
    @DisplayName("a reader who has written nothing may use the replica")
    void noWriteMeansNothingToWaitFor() throws Exception {
        ReplicaFreshness freshness = pollingAt("0/1000");

        // The case that carries almost all the traffic: anonymous visitors on the public
        // listing. They have no writes of their own, so there is nothing they could fail to see.
        assertThat(freshness.isSafeFor(null)).isTrue();
        assertThat(freshness.isSafeFor(SOMEBODY)).isTrue();
    }

    @Test
    @DisplayName("a reader whose write the replica has not replayed must use the primary")
    void aheadOfTheReplicaMeansPrimary() throws Exception {
        ReplicaFreshness freshness = pollingAt("0/1000");

        writes.recordWrite(SOMEBODY, "0/2000");

        assertThat(freshness.isSafeFor(SOMEBODY)).isFalse();
        // And only for them. Everybody else is unaffected, which is the point of tracking this
        // per user rather than globally - one person writing must not push the whole site onto
        // the primary.
        assertThat(freshness.isSafeFor(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("once the replica catches up, the reader goes back to it")
    void caughtUpMeansReplica() throws Exception {
        ReplicaFreshness freshness = pollingAt("0/2000");

        writes.recordWrite(SOMEBODY, "0/2000");

        // Self-releasing: no timer to expire, no sticky session to unstick. The moment the
        // replica holds the write, the guard stops applying.
        assertThat(freshness.isSafeFor(SOMEBODY)).isTrue();
    }

    @Test
    @DisplayName("a replica that has never been polled is not trusted")
    void unpolledMeansPrimary() {
        ReplicaFreshness freshness = new ReplicaFreshness(mock(DataSource.class), writes);

        // Start-up, before the first poll has run. Treating "no information" as "caught up"
        // would send every read to an unverified node for the first fraction of a second.
        assertThat(freshness.isSafeFor(SOMEBODY)).isFalse();
    }

    @Test
    @DisplayName("a replica that cannot be reached is not trusted")
    void unreachableMeansPrimary() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        ReplicaFreshness freshness = new ReplicaFreshness(broken, writes);

        freshness.poll();

        assertThat(freshness.isSafeFor(SOMEBODY)).isFalse();
    }

    @Test
    @DisplayName("a promoted standby stops being usable as a replica")
    void promotedStandbyMeansPrimary() throws Exception {
        // pg_last_wal_replay_lsn() returns null on a server that is not in recovery. That is
        // exactly what a standby does the instant it is promoted - and a null must not be read
        // as "no lag", or reads would keep going to a node that is now a second primary.
        ReplicaFreshness freshness = pollingAt(null);

        assertThat(freshness.isSafeFor(SOMEBODY)).isFalse();
    }

    /** A ReplicaFreshness whose replica reports {@code position}, already polled once. */
    private ReplicaFreshness pollingAt(String position) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("select pg_last_wal_replay_lsn()")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn(position);

        ReplicaFreshness freshness = new ReplicaFreshness(dataSource, writes);
        freshness.poll();
        return freshness;
    }
}
