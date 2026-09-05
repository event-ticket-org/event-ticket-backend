package com.eventticket.admission.support;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * requirements/007 criterion 12: scan attempts are rate-limited per device.
 *
 * <p>In memory, and that is a deliberate reading of nfr.md rather than a shortcut: it states a
 * single application instance, a single Postgres and no high-availability requirement. A second
 * instance would halve this limit's effectiveness, which is a known consequence of an
 * architecture the knowledge base chose, not an oversight here.
 *
 * <p>The limit is generous on purpose. A scanner reads QR codes continuously, so a camera
 * resting on one code fires the same scan many times a second; the door failing because someone
 * held their phone still would be a worse outcome than the abuse this prevents. The client
 * should debounce, and the server should not depend on it doing so.
 *
 * <p>A token bucket rather than a fixed window: a window resets on the clock, so a queue that
 * arrives on the wrong side of a second gets refused while an idle device banks nothing.
 */
@Component
public class DeviceRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DeviceRateLimiter.class);

    private record Bucket(double tokens, long atNanos) {}

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double perSecond;
    private final double burst;

    public DeviceRateLimiter(@Value("${app.admission.scans-per-second:5}") double perSecond,
                      @Value("${app.admission.burst:30}") double burst) {
        this.perSecond = perSecond;
        this.burst = burst;
    }

    /** Throws {@code RATE_LIMITED}, which the contract maps to 429, rather than returning false. */
    public void requireWithinLimit(String deviceId) {
        long now = System.nanoTime();

        Bucket updated = buckets.compute(deviceId, (id, previous) -> {
            if (previous == null) {
                return new Bucket(burst - 1, now);
            }
            double refilled = Math.min(burst,
                    previous.tokens() + Duration.ofNanos(now - previous.atNanos()).toNanos()
                            / 1_000_000_000.0 * perSecond);
            return new Bucket(refilled - 1, now);
        });

        if (updated.tokens() < 0) {
            // Put the token back, so a refused scan does not dig the device deeper into debt.
            buckets.put(deviceId, new Bucket(0, now));
            log.warn("Scan rate limit hit deviceId={}", deviceId);
            throw new ApiException(ErrorCodes.RATE_LIMITED,
                    "This device is scanning faster than the door can be. Wait a moment.");
        }
    }

    /** Tests need a device with no history; nothing in production calls this. */
    public void forget(String deviceId) {
        buckets.remove(deviceId);
    }
}
