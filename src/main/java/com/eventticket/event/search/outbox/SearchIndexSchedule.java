package com.eventticket.event.search.outbox;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * When the indexing work runs, kept apart from what it does.
 *
 * <p>The split is not ceremony. The suite drives {@link IndexPendingEvents#drain()} directly
 * and asserts on the result, and a copy of it also firing every two seconds deadlocked against
 * the {@code TRUNCATE} that cleans up between tests: the drain holds an {@code AccessShareLock}
 * on {@code event} while the truncate wants {@code AccessExclusiveLock} on it, and Postgres
 * resolved that by killing one of them. The failure landed in an unrelated payment test, which
 * is how a background job announces itself.
 *
 * <p>So the clock is a bean of its own and is off in tests. The work is still tested - harder
 * than a schedule would allow, because a test that waited two seconds for a tick would be slow
 * and occasionally green for the wrong reason.
 *
 * <p>Registered by {@code SearchIndexConfiguration} rather than component-scanned, so that the
 * condition on the cluster and the condition on the clock can both apply - two
 * {@code @ConditionalOnProperty} annotations do not stack on one class.
 */
public class SearchIndexSchedule {

    private final IndexPendingEvents incremental;
    private final RebuildSearchIndex rebuild;

    public SearchIndexSchedule(IndexPendingEvents incremental, RebuildSearchIndex rebuild) {
        this.incremental = incremental;
        this.rebuild = rebuild;
    }

    /**
     * Frequent, because the delay is visible: somebody publishes an Event and then searches for
     * it. Two seconds is under the time it takes to type a query, and the work when the outbox
     * is empty is one indexed count.
     */
    @Scheduled(fixedDelayString = "${app.search.drain-interval:PT2S}")
    public void drain() {
        incremental.drain();
    }

    /**
     * 03:20, after the 03:00 database backup rather than during it. A cron rather than a fixed
     * delay: this should happen when nobody is buying, and a delay measured from the last run
     * drifts across the day until it does not.
     */
    @Scheduled(cron = "${app.search.rebuild-cron:0 20 3 * * *}")
    public void nightly() {
        rebuild.rebuild();
    }
}
