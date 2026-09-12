package com.eventticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.support.ApiTest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The same verification link, opened twice at once.
 *
 * <h2>Why this is not a contrived race</h2>
 *
 * <p>It was found by running the application behind the real frontend, not by imagining a
 * scenario. React's development mode invokes an effect twice, so the verify-email page fires
 * <strong>two requests about five milliseconds apart</strong>, and the backend log showed
 * exactly that: one {@code Verified email} and, on another thread, a
 * {@code WriteConflict (112)} that reached the browser as
 * {@code 500 "The request could not be completed."}
 *
 * <p>A double-submit needs no framework to happen, either - a double-click, a mail client
 * prefetching the link, a browser retrying - so this is the ordinary case rather than the
 * exotic one. It is the <em>happy path of every registration</em>.
 *
 * <h2>What each datastore does with it</h2>
 *
 * <p>Postgres serialised the two: the second transaction <em>blocked</em> on the token row,
 * woke when the first committed, re-read it, found it consumed, and answered
 * {@code 410 Gone - "that link has already been used"}. One success, one accurate refusal.
 *
 * <p>MongoDB aborts instead of waiting. The second transaction dies with
 * {@code TransientTransactionError} and the driver's contract is that the caller retries -
 * which is what {@code TransientRetry} now does. On the retry the token is genuinely consumed,
 * so the domain rule produces the 410 that Postgres produced by waiting.
 *
 * <p>This is the second instance of that failure in this migration, after the seat-hold race,
 * and the pair is the general lesson: <strong>any {@code @Transactional} use case where two
 * callers can touch one document is a 500 waiting to happen</strong>, and it is invisible until
 * something actually issues the two requests at once.
 */
class VerifyEmailConcurrencyTest extends ApiTest {

    @Test
    @DisplayName("the same link opened twice verifies once and refuses once, with no server error")
    void doubleSubmitIsRefusedNotBroken() throws Exception {
        var registered = http.postForEntity("/auth/register",
                new com.eventticket.api.model.RegisterRequest(
                        "double@example.com", "correct-horse-battery", "Double"),
                Void.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String token = email.verificationTokenFor("double@example.com")
                .orElseThrow(() -> new AssertionError("no verification email was sent"));

        List<ResponseSummary> outcomes = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<? extends Future<?>> attempts = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        var response = exchange(HttpMethod.POST, "/auth/verify-email", null,
                                Map.of("token", token), Object.class);
                        outcomes.add(new ResponseSummary(
                                HttpStatus.valueOf(response.getStatusCode().value()),
                                codeOf(response.getBody())));
                        return null;
                    })).toList();

            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(outcomes).hasSize(2);

        // Exactly one verification. Two would mean the token was spent twice.
        assertThat(outcomes).filteredOn(o -> o.status() == HttpStatus.OK).hasSize(1);

        // And the loser is told the truth rather than that the server broke. This is the
        // assertion that fails without the retry: the status is 500 and the code is
        // VALIDATION_FAILED, which tells a person nothing and invites them to try a link that
        // has in fact already worked.
        assertThat(outcomes).filteredOn(o -> o.status() != HttpStatus.OK)
                .allSatisfy(loser -> assertThat(loser.status()).isEqualTo(HttpStatus.GONE));
    }

    /**
     * The same shape again, on the password reset link, and included because the pair is the
     * finding rather than either one alone: this is a <em>class</em> of defect that arrives with
     * optimistic transactions, not two unlucky use cases.
     */
    @Test
    @DisplayName("a reset link opened twice resets once and refuses once, with no server error")
    void doubleSubmittedResetIsRefusedNotBroken() throws Exception {
        signUp("forgetful@example.com");
        var asked = http.postForEntity("/auth/forgot-password",
                new com.eventticket.api.model.ForgotPasswordRequest("forgetful@example.com"),
                Void.class);
        assertThat(asked.getStatusCode().is2xxSuccessful()).isTrue();

        String token = email.resetTokenFor("forgetful@example.com")
                .orElseThrow(() -> new AssertionError("no reset email was sent"));

        List<ResponseSummary> outcomes = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<? extends Future<?>> attempts = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        var response = exchange(HttpMethod.POST, "/auth/reset-password", null,
                                Map.of("token", token, "password", "a-brand-new-password"),
                                Object.class);
                        outcomes.add(new ResponseSummary(
                                HttpStatus.valueOf(response.getStatusCode().value()),
                                codeOf(response.getBody())));
                        return null;
                    })).toList();
            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o.status() == HttpStatus.OK).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.status() != HttpStatus.OK)
                .allSatisfy(loser -> assertThat(loser.status()).isEqualTo(HttpStatus.GONE));
    }

    private record ResponseSummary(HttpStatus status, String code) {}

    private static String codeOf(Object body) {
        return body instanceof Map<?, ?> map && map.get("code") instanceof String code ? code : null;
    }
}
