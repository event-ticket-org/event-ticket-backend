package com.eventticket.shared.persistence;

/**
 * Carries a transaction's read-only intent to the router, because Spring's own flag is not set
 * early enough to use.
 *
 * <h2>Why {@code TransactionSynchronizationManager} cannot answer this</h2>
 *
 * <p>The obvious implementation asks
 * {@code TransactionSynchronizationManager.isCurrentTransactionReadOnly()} inside
 * {@code determineCurrentLookupKey}. It compiles, it runs, and <strong>every transaction goes to
 * the primary.</strong>
 *
 * <p>{@code AbstractPlatformTransactionManager} starts a transaction in this order:
 *
 * <pre>
 *   doBegin(transaction, definition);          // the connection is acquired here
 *   prepareSynchronization(status, definition);  // the read-only flag is published here
 * </pre>
 *
 * <p>and {@code TenantAwareTransactionManager.doBegin} needs a real connection immediately, to
 * issue {@code SET LOCAL ROLE} before any application query runs. So the router is asked which
 * node to use at a moment when the flag still reads false for everybody.
 * {@link org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy} does not rescue this
 * - it defers the connection until the first statement, and {@code doBegin} *is* the first
 * statement.
 *
 * <p>This was not reasoned out in advance. It was found by pausing replication, writing a row
 * straight to the primary, and watching the application return it anyway - the replica never
 * received a single query and nothing anywhere said so. <strong>A routing bug in this direction
 * is invisible: it fails safe, serves correct data, and quietly does nothing.</strong> Which is
 * exactly why the end-to-end check asserts the replica is used rather than assuming it.
 *
 * <p>So the transaction manager, which is handed the {@link
 * org.springframework.transaction.TransactionDefinition} and therefore knows the answer before
 * anybody asks, puts it here first.
 */
public final class RoutingContext {

    private static final ThreadLocal<Boolean> READ_ONLY = new ThreadLocal<>();

    private RoutingContext() {
    }

    /** Called from {@code doBegin}, before the connection is acquired. */
    public static void beginTransaction(boolean readOnly) {
        READ_ONLY.set(readOnly);
    }

    /**
     * Called when the transaction completes. Clearing matters on a pooled thread: a leftover
     * {@code true} would offer the next piece of work on this thread to the replica, and that
     * work might be a write.
     */
    public static void endTransaction() {
        READ_ONLY.remove();
    }

    static boolean isReadOnly() {
        return Boolean.TRUE.equals(READ_ONLY.get());
    }
}
