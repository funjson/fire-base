package dev.infinityknowledge.controlplane.observability;

import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.spi.trace.TraceSink;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;

/** Adds bounded-cardinality retrieval metrics while preserving the configured trace sink. */
public final class MeteredTraceSink implements TraceSink {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeteredTraceSink.class);
    private static final Set<String> STEP_NAMES = Set.of(
            "ACCESS_POLICY",
            "QUERY_ANALYSIS",
            "QUERY_PLANNING",
            "RETRIEVER_KEYWORD",
            "RETRIEVER_VECTOR",
            "RETRIEVER_GRAPH",
            "RETRIEVER_PAGE",
            "ACTIVE_REVISION_GUARD",
            "RRF_FUSION",
            "RERANK",
            "EVIDENCE_BUILD"
    );
    private static final Set<String> STEP_STATUSES = Set.of(
            "SUCCEEDED",
            "FAILED",
            "NOT_CONFIGURED",
            "TIMEOUT",
            "DEADLINE_EXCEEDED",
            "REJECTED",
            "CANCELLED",
            "DEGRADED"
    );
    private final TraceSink delegate;
    private final MeterRegistry registry;

    public MeteredTraceSink(TraceSink delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void append(RetrievalTrace trace) {
        Objects.requireNonNull(trace, "trace must not be null");
        try {
            recordMetrics(trace);
        } catch (RuntimeException metricFailure) {
            // Metrics must never make trace persistence or the user request fail.
            LOGGER.warn(
                    "Retrieval metric recording failed: failureType={}",
                    metricFailure.getClass().getSimpleName()
            );
        }
        delegate.append(trace);
    }

    private void recordMetrics(RetrievalTrace trace) {
        String outcome = trace.steps().stream()
                .allMatch(step -> "SUCCEEDED".equals(step.status()))
                ? "SUCCEEDED"
                : "DEGRADED";
        Timer.builder("infinity.knowledge.retrieval.duration")
                .description("End-to-end knowledge retrieval duration")
                .tag("outcome", outcome)
                .register(registry)
                .record(trace.totalDuration());
        DistributionSummary.builder("infinity.knowledge.retrieval.results")
                .description("Evidence result count per retrieval")
                .tag("outcome", outcome)
                .register(registry)
                .record(trace.resultCount());
        for (RetrievalStepTrace step : trace.steps()) {
            Timer.builder("infinity.knowledge.retrieval.step.duration")
                    .description("Knowledge retrieval step duration")
                    .tag("step", normalized(step.name(), STEP_NAMES))
                    .tag("status", normalized(step.status(), STEP_STATUSES))
                    .register(registry)
                    .record(step.duration());
        }
    }

    private static String normalized(String value, Set<String> allowed) {
        return allowed.contains(value) ? value : "OTHER";
    }
}
