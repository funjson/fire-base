package dev.infinityknowledge.controlplane.observability;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MeteredTraceSinkTest {

    @Test
    void delegatesTraceAndNormalizesAllMetricTagsToBoundedValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<RetrievalTrace> persisted = new AtomicReference<>();
        MeteredTraceSink sink = new MeteredTraceSink(persisted::set, registry);
        RetrievalTrace trace = new RetrievalTrace(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new TenantId("tenant-secret-123"),
                new PrincipalId("principal-secret-456"),
                "hash-only",
                List.of(
                        new RetrievalStepTrace(
                                "ACCESS_POLICY", Duration.ofMillis(2), 1, 1, "SUCCEEDED"
                        ),
                        new RetrievalStepTrace(
                                "tenant-secret-123", Duration.ofMillis(3), 1, 0, "dynamic-value"
                        )
                ),
                Duration.ofMillis(9),
                4,
                Instant.parse("2026-08-11T00:00:00Z")
        );

        sink.append(trace);

        assertEquals(trace, persisted.get());
        assertEquals(1L, registry.get("infinity.knowledge.retrieval.duration")
                .tag("outcome", "DEGRADED").timer().count());
        assertEquals(4.0D, registry.get("infinity.knowledge.retrieval.results")
                .tag("outcome", "DEGRADED").summary().totalAmount());
        assertEquals(1L, registry.get("infinity.knowledge.retrieval.step.duration")
                .tag("step", "OTHER").tag("status", "OTHER").timer().count());
        assertFalse(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> tag.getValue().contains("secret")));
    }
}
