package com.eventticket.shared.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Decides whether the replica is far enough along to answer a particular person's read.
 *
 * <h2>Why the replica's position is polled rather than asked for</h2>
 *
 * <p>The obvious implementation asks the replica how far it has replayed at the moment a routing
 * decision is needed. That cannot work: asking requires a connection, and which connection to
 * use is the decision being made. It is circular, and it would also put a round trip in front of
 * every read.
 *
 * <p>So a scheduled task holds the answer up to date and routing reads a field. The cached value
 * is by definition slightly behind what the replica has really replayed, which means this
 * occasionally says "not caught up" when it just has. <strong>That is the safe direction</strong>
 * - the cost is a read served by the primary that need not have been, and the alternative error
 * is a user being shown a stale version of something they just wrote.
 *
 * <h2>Everything that goes wrong routes to the primary</h2>
 *
 * <p>Replica unreachable, poll failing, poll succeeded but long ago, position unparseable, no
 * position yet because the application has just started: all of them answer "not safe". The
 * feature degrades to exactly the behaviour of the application before it existed - every query
 * on the primary - rather than to stale reads.
 */
public class ReplicaFreshness {

    private static final Logger log = LoggerFactory.getLogger(ReplicaFreshness.class);

    /**
     * How old a poll may be before its answer is disowned.
     *
     * <p>Not the same thing as the poll interval, and deliberately a small multiple of it: one
     * slow or failed poll should not push every reader onto the primary, but a poller that has
     * been stuck for seconds is reporting a position that means nothing.
     */
    private static final Duration TRUSTED_FOR = Duration.ofSeconds(5);

    private final DataSource replica;
    private final LastWriteStore lastWrites;

    private volatile Lsn replayed;
    private volatile Instant polledAt = Instant.EPOCH;

    public ReplicaFreshness(DataSource replica, LastWriteStore lastWrites) {
        this.replica = replica;
        this.lastWrites = lastWrites;
    }

    /**
     * True when a read for this user may be served by the replica.
     *
     * <p>A user who has not written recently always qualifies, which is the case that matters
     * most: anonymous traffic on the public listing has no writes of its own, and it is also the
     * overwhelming majority of reads.
     */
    public boolean isSafeFor(UUID userId) {
        Optional<String> theirWrite = lastWrites.lastWrite(userId);
        if (theirWrite.isEmpty()) {
            return isReplicaUsable();
        }
        Lsn required;
        try {
            required = Lsn.parse(theirWrite.get());
        } catch (RuntimeException malformed) {
            log.warn("Unparseable write position for a user, routing to the primary", malformed);
            return false;
        }
        Lsn current = replayed;
        return isReplicaUsable() && current != null && current.hasReached(required);
    }

    private boolean isReplicaUsable() {
        return replayed != null && polledAt.plus(TRUSTED_FOR).isAfter(Instant.now());
    }

    /**
     * Two hundred milliseconds, which is chosen against the thing it protects rather than
     * against the replica's own speed: it bounds how long a user who has just written keeps
     * being sent to the primary after the replica has actually caught up. Polling faster buys
     * very little; polling much slower turns a healthy replica into an idle one.
     *
     * <p>Note the scheduler pool has to have room for this - see {@code application.yml}.
     */
    @Scheduled(fixedDelayString = "${app.datasource.replica.poll-interval:PT0.2S}")
    public void poll() {
        try (Connection connection = replica.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select pg_last_wal_replay_lsn()")) {
            if (rows.next()) {
                String position = rows.getString(1);
                // Null on a server that is not in recovery - which happens the moment this
                // standby is promoted. It is then a primary, has no replay position, and must
                // not be treated as a caught-up replica.
                replayed = position == null ? null : Lsn.parse(position);
                polledAt = Instant.now();
            }
        } catch (Exception unreachable) {
            // Left deliberately quiet at debug: a replica that is down produces one of these
            // every 200ms, and burying the log is how the next real problem gets missed. The
            // effect is visible anyway - isReplicaUsable() goes false and reads move.
            log.debug("Could not read the replica's replay position", unreachable);
        }
    }
}
