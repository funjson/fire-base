package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证数据库 JSON 只按显式类型进行可逆编解码。 */
class ObservationJsonCodecTest {
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("space-a");
    private static final KnowledgeSpaceId OTHER_SPACE = new KnowledgeSpaceId("space-b");
    private static final String CONFIG = "a".repeat(64);

    @Test
    void roundTripsEveryStronglyTypedPayloadAndSortedSpaces() {
        ObservationJsonCodec codec = new ObservationJsonCodec();
        List<RetrievalObservationPayload> payloads = allPayloads();

        assertEquals(
                EnumSet.allOf(RetrievalObservationStage.class),
                payloads.stream()
                        .map(RetrievalObservationPayload::stage)
                        .collect(Collectors.toSet())
        );
        for (RetrievalObservationPayload payload : payloads) {
            assertEquals(
                    payload,
                    codec.readPayload(
                            payload.stage(),
                            payload.getClass().getSimpleName(),
                            codec.json(payload)
                    ),
                    () -> "payload round-trip failed for stage " + payload.stage()
            );
        }
        assertEquals(
                Set.of(SPACE, OTHER_SPACE),
                codec.readSpaceIds(codec.spaceIds(Set.of(
                        OTHER_SPACE, SPACE
                )))
        );
    }

    @Test
    void roundTripsRuntimeAndOfflineGoldFactsWithoutPolymorphicTyping() {
        ObservationJsonCodec codec = new ObservationJsonCodec();
        UUID sourceEventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        MetricDimensions runtimeDimensions = dimensions(RetrievalObservationPurpose.ONLINE)
                .terminalStatus(RetrievalTerminalStatus.SUFFICIENT)
                .stopReason(RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED)
                .build();
        MetricFact.Runtime runtime = new MetricFact.Runtime(
                sourceEventId, executionId, new TenantId("tenant-a"),
                "retrieval.request.count", 1, MetricFact.Aggregation.COUNT,
                1.0D, runtimeDimensions, NOW
        );

        UUID caseId = UUID.randomUUID();
        MetricDimensions goldDimensions = dimensions(RetrievalObservationPurpose.EVALUATION)
                .queryCase(caseId)
                .build();
        MetricFact.OfflineGold gold = new MetricFact.OfflineGold(
                sourceEventId, executionId, new TenantId("tenant-a"),
                UUID.randomUUID(), 2L, caseId, "retrieval.chunk.recall", 1,
                MetricFact.Aggregation.MACRO_AVERAGE, 0.75D,
                goldDimensions, NOW
        );

        assertEquals(runtime, codec.readFact("RUNTIME", codec.json(runtime)));
        assertEquals(gold, codec.readFact("OFFLINE_GOLD", codec.json(gold)));
    }

    private static MetricDimensions.Builder dimensions(RetrievalObservationPurpose purpose) {
        return MetricDimensions.builder()
                .config(CONFIG)
                .attempt(0)
                .status(RetrievalObservationStatus.SUCCEEDED)
                .purpose(purpose)
                .timeSlice(NOW, MetricDimensions.TimeSliceGranularity.HOUR);
    }

    /**
     * 为每个阶段提供包含典型嵌套值的载荷，避免某个协议类型只在真实数据库重放时失败。
     */
    private static List<RetrievalObservationPayload> allPayloads() {
        var component = new RetrievalObservationPayload.ComponentVersion(
                "retriever", "built-in", "model-v1", "v1"
        );
        var fingerprint = fingerprint("b");
        var candidate = new RetrievalObservationPayload.CandidateIdentity(
                uuid(1), uuid(2), uuid(3), SPACE
        );
        var rankedCandidate = new RetrievalObservationPayload.RankedCandidate(
                candidate, 1, 0.85D
        );
        var usage = new RetrievalObservationPayload.UsageCount(1, true, 12, 4);

        return List.of(
                new RetrievalObservationPayload.ExecutionStarted(
                        fingerprint, 8, 2
                ),
                new RetrievalObservationPayload.SpaceRoutingCompleted(
                        2,
                        List.of(
                                new RetrievalObservationPayload.RankedSpace(SPACE, 1),
                                new RetrievalObservationPayload.RankedSpace(OTHER_SPACE, 2)
                        )
                ),
                new RetrievalObservationPayload.ConfigurationResolved(
                        SPACE,
                        3L,
                        List.of(component),
                        List.of(new RetrievalObservationPayload.DataIndexVersion(
                                SPACE, uuid(4), "generation-v1"
                        ))
                ),
                new RetrievalObservationPayload.QueryAnalysisCompleted(
                        fingerprint,
                        Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.VECTOR),
                        24,
                        component
                ),
                new RetrievalObservationPayload.QueryPlanningCompleted(
                        component,
                        List.of(
                                new RetrievalObservationPayload.QueryVariantFact(
                                        "original", "ORIGINAL", fingerprint
                                ),
                                new RetrievalObservationPayload.QueryVariantFact(
                                        "rewrite-1", "REWRITE", fingerprint("c")
                                )
                        ),
                        usage
                ),
                new RetrievalObservationPayload.RetrievalPlanCompleted(
                        "HYBRID",
                        List.of(
                                new RetrievalObservationPayload.RetrievalBranchPlan(
                                        "keyword-original", "original",
                                        RetrievalChannel.KEYWORD, SPACE,
                                        "generation-v1", 12
                                ),
                                new RetrievalObservationPayload.RetrievalBranchPlan(
                                        "vector-original", "original",
                                        RetrievalChannel.VECTOR, SPACE,
                                        "generation-v1", 12
                                )
                        )
                ),
                new RetrievalObservationPayload.RetrievalBranchCompleted(
                        "keyword-original", "KEYWORD", "original", fingerprint,
                        RetrievalChannel.KEYWORD, component, "generation-v1",
                        12, 1, List.of(rankedCandidate)
                ),
                new RetrievalObservationPayload.FusionCompleted(
                        "RRF", component, 2, 1, 60,
                        List.of(new RetrievalObservationPayload.FusedCandidateFact(
                                candidate,
                                1,
                                0.75D,
                                List.of(
                                        new RetrievalObservationPayload.RankContribution(
                                                "keyword-original",
                                                RetrievalChannel.KEYWORD,
                                                1,
                                                0.5D
                                        ),
                                        new RetrievalObservationPayload.RankContribution(
                                                "vector-original",
                                                RetrievalChannel.VECTOR,
                                                2,
                                                0.25D
                                        )
                                )
                        ))
                ),
                new RetrievalObservationPayload.RerankCompleted(
                        "MODEL_RERANK", component, true, false, 1,
                        List.of(new RetrievalObservationPayload.RerankedCandidateFact(
                                candidate,
                                1,
                                1,
                                RetrievalObservationPayload.OptionalScore.of(0.91D)
                        )),
                        usage
                ),
                new RetrievalObservationPayload.CoverageCheckCompleted(
                        component,
                        1,
                        1,
                        2,
                        RetrievalObservationPayload.OptionalCount.of(1),
                        RetrievalObservationPayload.OptionalScore.of(0.7D),
                        RetrievalObservationPayload.OptionalScore.of(0.65D),
                        RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT,
                        "SUFFICIENCY_THRESHOLD_REACHED",
                        usage
                ),
                new RetrievalObservationPayload.ChainNodeEvaluated(
                        "NEXT_SPACE", true, "COVERAGE_INSUFFICIENT", 2
                ),
                new RetrievalObservationPayload.ChainNodeCompleted(
                        "QUERY_REWRITE", "REWRITE", "APPLIED", 1, 1,
                        RetrievalObservationPayload.OptionalScore.of(0.4D),
                        RetrievalObservationPayload.OptionalScore.of(0.7D),
                        usage
                ),
                new RetrievalObservationPayload.SpaceChanged(
                        SPACE, OTHER_SPACE, "NEXT_SPACE"
                ),
                new RetrievalObservationPayload.EvidenceBuildCompleted(
                        1, List.of(rankedCandidate)
                ),
                new RetrievalObservationPayload.ExecutionTerminal(
                        RetrievalTerminalStatus.INSUFFICIENT,
                        RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED,
                        2,
                        1,
                        false,
                        List.of()
                ),
                new RetrievalObservationPayload.StageFailed(
                        "SPACE_ROUTING", 2
                )
        );
    }

    private static RetrievalObservationPayload.TextFingerprint fingerprint(String digit) {
        return new RetrievalObservationPayload.TextFingerprint(
                "HMAC_SHA256", "key-v1", digit.repeat(64)
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
