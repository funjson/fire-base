package dev.infinityknowledge.evaluation;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.Citation;
import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationOverride;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.KnowledgeGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluationRunnerTest {

    @Test
    void computesMacroRecallMrrAndNdcg() {
        DocumentId documentA = DocumentId.random();
        DocumentId documentB = DocumentId.random();
        DocumentId documentC = DocumentId.random();
        KnowledgeQuery firstQuery = query("first");
        KnowledgeQuery secondQuery = query("second");
        KnowledgeGateway gateway = query -> bundle(
                query,
                query.text().equals("first")
                        ? List.of(evidence(DocumentId.random()), evidence(documentA))
                        : List.of(evidence(documentC))
        );
        RetrievalEvaluationRunner runner = new RetrievalEvaluationRunner(gateway);

        RetrievalEvaluationReport report = runner.run(List.of(
                new RetrievalEvaluationCase(
                        UUID.randomUUID(),
                        firstQuery,
                        Set.of(documentA, documentB),
                        Set.of()
                ),
                new RetrievalEvaluationCase(
                        UUID.randomUUID(),
                        secondQuery,
                        Set.of(documentC),
                        Set.of()
                )
        ), 2);

        assertThat(report.hitRate()).isEqualTo(1.0D);
        assertThat(report.recallAtK()).isEqualTo(0.75D);
        assertThat(report.mrr()).isEqualTo(0.75D);
        assertThat(report.ndcgAtK()).isBetween(0.69D, 0.70D);
        assertThat(report.failedCaseCount()).isZero();
        assertThat(report.cases()).hasSize(2);
    }

    @Test
    void isolatesFailedCasesAndKeepsTheRunReport() {
        KnowledgeQuery failed = query("failed");
        KnowledgeQuery succeeded = query("succeeded");
        DocumentId expected = DocumentId.random();
        KnowledgeGateway gateway = query -> {
            if (query.text().equals("failed")) {
                throw new IllegalStateException("provider unavailable");
            }
            return bundle(query, List.of(evidence(expected)));
        };

        RetrievalEvaluationReport report = new RetrievalEvaluationRunner(gateway).run(
                List.of(
                        new RetrievalEvaluationCase(
                                UUID.randomUUID(), failed, Set.of(expected), Set.of()
                        ),
                        new RetrievalEvaluationCase(
                                UUID.randomUUID(), succeeded, Set.of(expected), Set.of()
                        )
                ),
                2
        );

        assertThat(report.failedCaseCount()).isEqualTo(1);
        assertThat(report.hitRate()).isEqualTo(0.5D);
        assertThat(report.cases().getFirst().succeeded()).isFalse();
        assertThat(report.cases().getFirst().errorCode())
                .isEqualTo("EVALUATION_ILLEGAL_STATE_EXCEPTION");
        assertThat(report.cases().get(1).traceId()).isNotNull();
    }

    private static KnowledgeQuery query(String text) {
        return new KnowledgeQuery(
                UUID.randomUUID(),
                new PrincipalContext(
                        new TenantId("tenant-a"),
                        new PrincipalId("evaluator"),
                        Set.of("knowledge-evaluator"),
                        Set.of(),
                        true
                ),
                text,
                Set.of(new KnowledgeSpaceId("engineering")),
                2,
                Map.of(),
                dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput.empty(),
                "",
                List.of(),
                RetrievalConfigurationOverride.empty(),
                RetrievalObservationPurpose.EVALUATION
        );
    }

    private static Evidence evidence(DocumentId documentId) {
        UUID chunkId = UUID.randomUUID();
        return new Evidence(
                chunkId,
                "evidence",
                0.8D,
                80,
                Set.of(RetrievalChannel.KEYWORD),
                new Citation(
                        documentId,
                        UUID.randomUUID(),
                        chunkId,
                        "title",
                        List.of("section"),
                        "urn:test:" + documentId.value()
                )
        );
    }

    private static EvidenceBundle bundle(
            KnowledgeQuery query,
            List<Evidence> evidences
    ) {
        return new EvidenceBundle(
                query.requestId(),
                UUID.randomUUID(),
                query.principal().tenantId(),
                evidences,
                RetrievalTerminalStatus.SUFFICIENT,
                RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED,
                false,
                List.of(query.spaceIds().iterator().next()),
                List.of("a".repeat(64)),
                List.of(),
                Instant.parse("2026-07-26T00:00:00Z")
        );
    }
}
