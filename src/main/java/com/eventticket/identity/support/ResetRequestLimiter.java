package com.eventticket.identity.support;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * nfr.md, Account recovery: reset requests are limited per address, and reset mail is capped in
 * total.
 *
 * <p>The endpoint is unauthenticated and sends mail to an address the caller names, so with no
 * limit it is a way to have this system deliver unbounded mail to a stranger - and the
 * reputational cost of that lands on the sending domain rather than on whoever asked for it.
 *
 * <p><strong>The total cap is what replaces a per-caller one, and the reason is this
 * deployment's shape.</strong> The application is reached through a tunnel and a reverse proxy,
 * so every request arrives from the proxy's address. A limit keyed on that is one global limit
 * whose failure mode is backwards: a single attacker trips it and locks every User out of
 * recovery. Trusting a forwarded header instead means keying the limit on a value the attacker
 * writes. A cap on the mail itself has neither problem and bounds the thing worth bounding.
 *
 * <p>Both are token buckets rather than fixed windows, for the reason
 * {@code DeviceRateLimiter} gives: a window resets on the clock, so whoever arrives on the
 * wrong side of it is refused while an idle caller banks nothing.
 *
 * <p>In memory, per instance - the same reading of nfr.md that the door's limiter makes, and
 * for the same stated reason: one application instance, one Postgres, no high availability.
 *
 * <p>A refusal is thrown, not returned, but it is thrown <em>before</em> the address is looked
 * up. That ordering is the whole reason this class can exist without undoing criterion 17: a
 * limit that only counted addresses that turned out to exist would answer differently for one
 * that does, which is the oracle the criterion refuses to be.
 */
@Component
public class ResetRequestLimiter {

    private static final Logger log = LoggerFactory.getLogger(ResetRequestLimiter.class);

    private record Bucket(double tokens, long atNanos) {}

    private final Map<String, Bucket> perAddress = new ConcurrentHashMap<>();
    private final AtomicReference<Bucket> total;
    private final double addressPerHour;
    private final double addressBurst;
    private final double totalPerHour;
    private final double totalBurst;

    public ResetRequestLimiter(
            @Value("${app.recovery.requests-per-hour-per-address:5}") double addressPerHour,
            @Value("${app.recovery.burst-per-address:3}") double addressBurst,
            @Value("${app.recovery.emails-per-hour:60}") double totalPerHour,
            @Value("${app.recovery.burst-total:30}") double totalBurst) {
        this.addressPerHour = addressPerHour;
        this.addressBurst = addressBurst;
        this.totalPerHour = totalPerHour;
        this.totalBurst = totalBurst;
        this.total = new AtomicReference<>(new Bucket(totalBurst, System.nanoTime()));
    }

    /**
     * Throws {@code RATE_LIMITED}, which the contract maps to 429.
     *
     * <p>The address is lower-cased for the key and for nothing else. Two requests differing
     * only in case are the same mailbox, and a limit that did not know it is one an attacker
     * steps around by holding shift.
     */
    public void requireWithinLimit(String emailAddress) {
        long now = System.nanoTime();

        Bucket address = perAddress.compute(emailAddress.toLowerCase(java.util.Locale.ROOT),
                (key, previous) -> take(previous, now, addressPerHour, addressBurst));
        if (address.tokens() < 0) {
            perAddress.put(emailAddress.toLowerCase(java.util.Locale.ROOT), new Bucket(0, now));
            // The address is logged and the refusal is not told apart from any other: whether
            // this address has an account is exactly what criterion 17 will not disclose, and
            // this log line is where somebody investigating can see what was being tried.
            log.warn("Password reset requests refused: address over its limit address={}", emailAddress);
            throw refusal();
        }

        Bucket capped = total.updateAndGet(previous -> take(previous, now, totalPerHour, totalBurst));
        if (capped.tokens() < 0) {
            total.set(new Bucket(0, now));
            log.error("Password reset mail capped: the system-wide hourly limit is exhausted, "
                    + "so legitimate resets are being refused. This is either abuse or a limit set too low.");
            throw refusal();
        }
    }

    private static Bucket take(Bucket previous, long now, double perHour, double burst) {
        if (previous == null) {
            return new Bucket(burst - 1, now);
        }
        double elapsedSeconds = Duration.ofNanos(now - previous.atNanos()).toNanos() / 1_000_000_000.0;
        double refilled = Math.min(burst, previous.tokens() + elapsedSeconds * perHour / 3600.0);
        return new Bucket(refilled - 1, now);
    }

    private static ApiException refusal() {
        return new ApiException(ErrorCodes.RATE_LIMITED,
                "Too many password reset requests. Wait a few minutes and try again.");
    }

    /** Tests need an address with no history; nothing in production calls this. */
    public void forget(String emailAddress) {
        perAddress.remove(emailAddress.toLowerCase(java.util.Locale.ROOT));
        total.set(new Bucket(totalBurst, System.nanoTime()));
    }
}
