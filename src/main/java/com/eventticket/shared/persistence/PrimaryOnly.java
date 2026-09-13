package com.eventticket.shared.persistence;

/**
 * Forces one block of work onto the primary, for the reads the LSN guard cannot protect.
 *
 * <p>The guard waits for the caller's <em>own</em> writes. A read that has to see something
 * <em>the system</em> wrote moments ago has nothing to wait for: the caller never wrote it, so
 * they look like a fresh reader and qualify for the replica.
 *
 * <p>There is one such read in this application. {@code CancelEvent.progress} is polled by a
 * manager while refunds are running, and the refunds are written by the cancellation as it
 * proceeds - so the position recorded when they triggered the cancel says nothing about the
 * refunds that have happened since, and on a lagging replica the progress would simply appear to
 * stop.
 *
 * <p>Deliberately a plain try/finally rather than an annotation and an aspect. One call site
 * does not justify AOP, and the explicit form puts the reason next to the code that needs it -
 * where an annotation would let the count grow quietly. If a third or fourth appears, that is
 * the moment to reconsider, not now.
 */
public final class PrimaryOnly {

    private static final ThreadLocal<Boolean> REQUIRED = new ThreadLocal<>();

    private PrimaryOnly() {
    }

    /**
     * Runs {@code work} against the primary, whatever the freshness of the replica.
     *
     * <p>Must wrap the call to the transactional method, not live inside it: the routing
     * decision is made when the transaction acquires its connection, which is before any code
     * in the method body runs.
     */
    public static <T> T run(java.util.function.Supplier<T> work) {
        REQUIRED.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            REQUIRED.remove();
        }
    }

    static boolean isRequired() {
        return Boolean.TRUE.equals(REQUIRED.get());
    }
}
