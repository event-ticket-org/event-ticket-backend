package com.eventticket.shared.mongo;

import com.mongodb.MongoException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a transaction again when MongoDB aborted it for contention rather than for a reason.
 *
 * <h2>Why this exists, and why Postgres needed no equivalent</h2>
 *
 * <p>This is the sharpest single difference between the two datastores in this system, and it
 * is not about syntax.
 *
 * <p>Postgres locks <strong>pessimistically</strong>. {@code hold_seats} took
 * {@code SELECT … FOR UPDATE} on the contested rows, so a second buyer <em>blocked</em>: they
 * waited, woke when the winner committed, re-evaluated the predicate and were refused with a
 * 409 naming the seats that had gone. The database queued the contention and every caller got
 * an answer about seats.
 *
 * <p>MongoDB transactions are <strong>optimistic</strong>. There is nothing to wait on: the
 * second writer's transaction is aborted outright with
 * {@code WriteConflict (112) / TransientTransactionError}, and the driver's contract is that
 * the caller retries the whole transaction. Nothing in this codebase did, so the abort travelled
 * up as a {@code DataIntegrityViolationException} and reached the buyer as
 * {@code 500 "The request could not be completed."}
 *
 * <p>Eleven of twelve buyers in a race got that 500 while the suite stayed green, because
 * {@code SeatHoldConcurrencyTest} counted anything that was not a 201 as a refusal. The winner
 * count was right in both builds. Only the losers' answer was wrong, and a test that counts
 * outcomes cannot see the difference.
 *
 * <h2>What this does and does not buy</h2>
 *
 * <p>It converts an abort back into the domain outcome: the retry re-reads, finds the seats
 * genuinely taken, and refuses with the 409 that names them. <strong>Behaviour matches
 * Postgres again. The cost profile does not.</strong> Postgres queued the losers once;
 * this makes them redo the entire unit of work - re-reading the Event, the seats and the
 * pricing tiers - and under a real on-sale spike a retry storm is precisely the wrong load to
 * add at the moment the system is already busiest. That is the honest shape of the trade: the
 * API is identical, and the behaviour under pressure is strictly worse.
 *
 * <p><strong>Only transient aborts are retried.</strong> An {@code ApiException} is an answer
 * and running it again would produce the same answer more slowly; a duplicate key is a real
 * constraint. The label is the driver's own, so this asks MongoDB whether the failure was
 * contention rather than inferring it from a message.
 *
 * <h2>Where it is applied, and how each site was found</h2>
 *
 * <p>Three use cases, and the way each surfaced is the more useful half of the story:
 *
 * <ul>
 *   <li>{@code BeginCheckout} - found by scripting <strong>six simultaneous buyers</strong>
 *       against both running stacks. Eleven of twelve racing buyers had been getting a 500
 *       where Postgres gave a 409 naming the seats.</li>
 *   <li>{@code VerifyEmail} - found by running the application behind the <strong>real
 *       frontend</strong>. React's development mode fires the effect twice, so two requests
 *       arrived five milliseconds apart; one verified, the other returned a 500. This is the
 *       happy path of every registration.</li>
 *   <li>{@code ResetPassword} - found by <strong>looking for the shape</strong> once the first
 *       two were understood, and confirmed with a test before anything was changed. Same
 *       token-consumption pattern, same abort, same 500.</li>
 * </ul>
 *
 * <p>Deliberately not applied to {@code ConfirmPayment}: a webhook touches one order and the
 * seats that order already holds, so two of them never contend for a document, and the
 * idempotency index already answers a redelivery. {@code CancelEvent} writing every seat of an
 * event <em>can</em> collide with a checkout in flight; it is rare, it has not been measured,
 * and saying so is better than wrapping it and implying it was.
 *
 * <p><strong>The general rule the three share:</strong> any transaction two callers can enter
 * for the same document is a 500 waiting to happen, and it is invisible until something issues
 * the requests at the same instant. A row lock made that case boring; an optimistic transaction
 * makes it a defect.
 *
 */
@Component
public class TransientRetry {

    private static final Logger log = LoggerFactory.getLogger(TransientRetry.class);

    /**
     * Five, and the number is a judgement rather than a default. A hold is decided in a single
     * round trip, so a caller that has lost five times has lost to five different winners and
     * the seats are genuinely gone - continuing past that point is spending server time to
     * deliver the same refusal later.
     */
    private static final int ATTEMPTS = 5;

    public <T> T execute(Supplier<T> work) {
        MongoException lastConflict = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException failure) {
                MongoException transientAbort = transientCause(failure);
                if (transientAbort == null) {
                    throw failure;
                }
                lastConflict = transientAbort;
                log.debug("Write conflict, retrying transaction (attempt {} of {})", attempt, ATTEMPTS);
                backOff(attempt);
            }
        }
        // Every attempt lost. This is a real failure and is reported as one rather than being
        // dressed up as a domain refusal: the caller never learned whether the seats were taken.
        log.warn("Gave up after {} write conflicts", ATTEMPTS);
        throw lastConflict;
    }

    /**
     * Spring wraps the driver's exception in a {@code DataAccessException}, so the label lives
     * somewhere down the cause chain rather than on what was thrown.
     */
    private static MongoException transientCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return mongo;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /**
     * Jittered, and the jitter is the point rather than politeness. Twelve buyers aborted by
     * the same winner would otherwise retry in the same millisecond and collide with each other
     * as well - the losers would have queued themselves into a second race of their own making.
     */
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 20L * attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a write conflict", interrupted);
        }
    }
}
