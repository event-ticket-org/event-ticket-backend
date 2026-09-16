package com.eventticket.shared.persistence.freshness;

import java.util.Optional;
import java.util.UUID;

/**
 * Remembers how far through the write-ahead log each user has written.
 *
 * <p>This is the state that makes read-your-own-writes possible without classifying endpoints.
 * A read may be served by a replica only once that replica has replayed past the position
 * recorded here for the person making it - so a buyer who has just created an order is sent to
 * the primary until their own order has arrived on the replica, and everybody else keeps using
 * the replica throughout.
 *
 * <h2>Why an interface for one implementation</h2>
 *
 * <p>{@code nfr.md} specifies a single application instance, so {@link InMemoryLastWriteStore} is
 * correct today and adding Redis would be a dependency bought for a problem this deployment does
 * not have. It is an interface because the day a second instance appears, that map stops being
 * shared and the guarantee quietly weakens - and the fix should be one new class rather than a
 * change to the transaction manager.
 *
 * <p>A cookie was considered and rejected: a WAL position is an internal detail of the
 * replication topology, and the client has no business carrying it.
 *
 * <h2>Losing this state is safe</h2>
 *
 * <p>Every implementation may forget freely - on restart, on expiry, under memory pressure. A
 * forgotten entry means the reader looks like somebody who has never written, which routes them
 * to the replica. That is the one direction in which being wrong is not safe, so entries are
 * kept for a generous multiple of any plausible replication lag, and the freshness check treats
 * an unreachable replica as stale. Between them, the failure modes land on the primary.
 */
public interface LastWriteStore {

    /**
     * Records that this user's write reached {@code lsn}.
     *
     * <p>The position must be read <strong>after</strong> the transaction commits.
     * {@code pg_current_wal_lsn()} evaluated inside the writing statement returns a position
     * from before the commit record is written, which is a guard that reports success while the
     * replica is genuinely behind - tested, and documented in
     * {@code docs/replication/02-this-project.md}.
     */
    void recordWrite(UUID userId, String lsn);

    /** The last position this user wrote to, if they have written recently. */
    Optional<String> lastWrite(UUID userId);
}
