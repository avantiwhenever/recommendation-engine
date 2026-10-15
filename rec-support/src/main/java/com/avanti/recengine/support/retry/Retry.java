package com.avanti.recengine.support.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/** Fixed-delay retry for startup dependencies that take a few seconds to accept connections. */
public final class Retry {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);

    private Retry() {
    }

    /**
     * Calls {@code attempt} until it returns without throwing, up to {@code maxAttempts} times,
     * sleeping {@code delay} after each failure.
     *
     * @param dependency what's being connected to, for log and exception messages (e.g. "Pinecone at http://...")
     * @throws IllegalStateException if every attempt fails, or the thread is interrupted while waiting
     */
    public static <T> T withFixedDelay(String dependency, int maxAttempts, Duration delay, Supplier<T> attempt) {
        RuntimeException lastFailure = null;
        for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("{} not ready yet (attempt {}/{}): {}", dependency, attemptNumber, maxAttempts, e.getMessage());
                sleep(delay, dependency);
            }
        }
        throw new IllegalStateException(
                "Could not connect to " + dependency + " after " + maxAttempts + " attempts", lastFailure);
    }

    private static void sleep(Duration delay, String dependency) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry " + dependency, e);
        }
    }
}
