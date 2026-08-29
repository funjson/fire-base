package dev.infinityknowledge.controlplane.api.projection;

/**
 * Result of an explicit dead-letter retry request.
 */
public record ProjectionRetryResponse(boolean requeued) {
}
