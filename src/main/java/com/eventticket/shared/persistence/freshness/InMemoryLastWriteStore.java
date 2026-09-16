package com.eventticket.shared.persistence.freshness;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single-instance implementation: a map that forgets after a while.
 *
 * <p>Correct for the deployment {@code nfr.md} describes, and wrong the moment there are two
 * application instances - a user whose write was recorded by one would look like a fresh reader
 * to the other, and be sent to a replica that has not caught up. That is why
 * {@link LastWriteStore} is an interface, and it is the first thing to replace when this scales
 * out.
 */
public class InMemoryLastWriteStore implements LastWriteStore {

    /**
     * Sixty seconds, and the number is chosen from the wrong direction on purpose.
     *
     * <p>It is not "how long a user needs read-your-own-writes for" - the entry stops mattering
     * the instant the replica passes it, which on a healthy cluster is milliseconds. It is "how
     * long replication could plausibly be behind before something else has already gone wrong",
     * and a minute is well past that: a replica a minute behind fails the freshness check anyway
     * and everything is routed to the primary regardless.
     *
     * <p>Expiring too early is the dangerous direction, because it makes a user who *has*
     * written look like one who has not.
     */
    private static final Duration RETENTION = Duration.ofSeconds(60);

    private final Map<UUID, Entry> lastWrites = new ConcurrentHashMap<>();

    private record Entry(String lsn, Instant recordedAt) {}

    @Override
    public void recordWrite(UUID userId, String lsn) {
        if (userId == null || lsn == null) {
            return;
        }
        lastWrites.put(userId, new Entry(lsn, Instant.now()));
        // Swept here rather than on a timer: writes are the rare operation, the map is keyed by
        // active user, and a background sweeper would be a second moving part to own.
        if (lastWrites.size() > 1_000) {
            evictExpired();
        }
    }

    @Override
    public Optional<String> lastWrite(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Entry entry = lastWrites.get(userId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.recordedAt().plus(RETENTION).isBefore(Instant.now())) {
            lastWrites.remove(userId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.lsn());
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        lastWrites.values().removeIf(entry -> entry.recordedAt().isBefore(cutoff));
    }
}
