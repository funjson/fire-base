package dev.infinityknowledge.retrieval.query;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationOverride;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固化硬过滤、显式软过滤和待收窄候选之间不可混淆的状态迁移。 */
class ResolvedConstraintsTest {

    @Test
    void neverTreatsCallerHardFilterAsRelaxable() {
        ResolvedConstraints constraints = ResolvedConstraints.initial(query(
                Map.of("language", "zh-CN"),
                RetrievalConstraintInput.empty()
        ));

        assertFalse(constraints.canRelax());
        assertTrue(constraints.relaxNext().isEmpty());
        assertEquals(Map.of("language", "zh-CN"), constraints.appliedFilters());
    }

    @Test
    void removesOnlyExplicitRelaxableFilter() {
        ResolvedConstraints constraints = ResolvedConstraints.initial(query(
                Map.of("sourceType", "UPLOAD"),
                new RetrievalConstraintInput(Map.of("language", "zh-CN"), Map.of())
        ));

        ResolvedConstraints relaxed = constraints.relaxNext().orElseThrow();

        assertEquals(Map.of("sourceType", "UPLOAD"), relaxed.appliedFilters());
        assertFalse(relaxed.canRelax());
    }

    @Test
    void narrowsOnlyWithCallerProvidedCandidate() {
        ResolvedConstraints constraints = ResolvedConstraints.initial(query(
                Map.of("language", "zh-CN"),
                new RetrievalConstraintInput(Map.of(), Map.of("sourceType", "UPLOAD"))
        ));

        assertEquals(Map.of("language", "zh-CN"), constraints.appliedFilters());
        ResolvedConstraints narrowed = constraints.narrowNext().orElseThrow();
        assertEquals(
                Map.of("language", "zh-CN", "sourceType", "UPLOAD"),
                narrowed.appliedFilters()
        );
        assertFalse(narrowed.canNarrow());
    }

    private KnowledgeQuery query(
            Map<String, String> filters,
            RetrievalConstraintInput constraints
    ) {
        return new KnowledgeQuery(
                UUID.randomUUID(),
                new PrincipalContext(
                        new TenantId("tenant-a"),
                        new PrincipalId("agent-a"),
                        Set.of("reader"),
                        Set.of("engineering"),
                        false
                ),
                "订单服务 ERR-1001 如何恢复",
                Set.of(new KnowledgeSpaceId("engineering")),
                5,
                filters,
                constraints,
                "",
                List.of(),
                RetrievalConfigurationOverride.empty(),
                RetrievalObservationPurpose.TEST_PLAZA
        );
    }
}
