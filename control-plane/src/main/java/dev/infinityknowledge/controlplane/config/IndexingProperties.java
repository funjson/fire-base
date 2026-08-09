package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounded background projection worker settings.
 */
@ConfigurationProperties(prefix = "infinity.knowledge.indexing")
public record IndexingProperties(
        int batchSize,
        Duration pollInterval,
        Duration leaseDuration,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff
) {

    /**
     * Applies safe defaults and validates worker resource bounds.
     */
    public IndexingProperties {
        batchSize = batchSize < 1 ? 8 : batchSize;
        if (batchSize > 256) {
            throw new IllegalArgumentException("batchSize must not exceed 256");
        }
        pollInterval = defaultPositive(pollInterval, Duration.ofSeconds(1), "pollInterval");
        leaseDuration = defaultPositive(leaseDuration, Duration.ofMinutes(2), "leaseDuration");
        maxAttempts = maxAttempts < 1 ? 5 : maxAttempts;
        if (maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must not exceed 100");
        }
        initialBackoff = defaultPositive(
                initialBackoff,
                Duration.ofSeconds(2),
                "initialBackoff"
        );
        maxBackoff = defaultPositive(maxBackoff, Duration.ofMinutes(5), "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be below initialBackoff");
        }
    }

    private static Duration defaultPositive(
            Duration value,
            Duration fallback,
            String name
    ) {
        Duration result = value == null ? fallback : value;
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }
}
