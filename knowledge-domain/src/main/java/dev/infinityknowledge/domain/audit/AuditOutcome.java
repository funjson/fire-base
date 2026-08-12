package dev.infinityknowledge.domain.audit;

/** Stable, low-cardinality outcome of a mutating HTTP request. */
public enum AuditOutcome {
    SUCCEEDED,
    FAILED
}
