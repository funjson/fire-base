package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Shared, deliberately low-frequency lease settings for recoverable offline work.
 */
@ConfigurationProperties(prefix = "infinity.knowledge.async")
public record AsyncRunProperties(
        Duration leaseDuration,
        Duration recoveryInterval,
        Duration initialDelay,
        int recoveryBatchSize
) {
    public AsyncRunProperties {
        leaseDuration = positive(leaseDuration, "leaseDuration");
        recoveryInterval = positive(recoveryInterval, "recoveryInterval");
        initialDelay = Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (recoveryBatchSize < 1 || recoveryBatchSize > 32) {
            throw new IllegalArgumentException("recoveryBatchSize must be between 1 and 32");
        }
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
