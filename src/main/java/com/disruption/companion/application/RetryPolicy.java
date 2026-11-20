package com.disruption.companion.application;

import java.time.Duration;
import java.util.function.Consumer;

public record RetryPolicy(int maxAttempts, Duration initialBackoff, Consumer<Duration> sleeper) {

    public static RetryPolicy withDefaults() {
        return new RetryPolicy(3, Duration.ofSeconds(1), RetryPolicy::sleep);
    }

    public Duration backoffForAttempt(int attempt) {
        return initialBackoff.multipliedBy(1L << (attempt - 1));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
