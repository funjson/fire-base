package dev.infinityknowledge.controlplane.api;

/**
 * Result of an explicit dead-letter retry request.
 */
public record ProjectionRetryResponse(boolean requeued) {
}
