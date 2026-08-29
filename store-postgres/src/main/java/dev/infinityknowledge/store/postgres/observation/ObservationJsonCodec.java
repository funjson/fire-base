package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.VisitedRetrievalConfiguration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 仅在 PostgreSQL 适配器内部使用的显式协议 JSON 编解码器。 */
final class ObservationJsonCodec {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() { };
    private static final TypeReference<List<ObservationIncompleteReason>> INCOMPLETE_REASONS =
            new TypeReference<>() { };
    private static final TypeReference<List<VisitedRetrievalConfiguration>> VISITED_CONFIGS =
            new TypeReference<>() { };

    private final JsonMapper mapper;

    ObservationJsonCodec() {
        this(JsonMapper.builder().build());
    }

    ObservationJsonCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(Objects.requireNonNull(value, "value must not be null"));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("retrieval observation value cannot be serialized", failure);
        }
    }

    String spaceIds(Set<KnowledgeSpaceId> values) {
        return json(values.stream().map(KnowledgeSpaceId::value).sorted().toList());
    }

    Set<KnowledgeSpaceId> readSpaceIds(String value) {
        return read(value, STRING_LIST).stream()
                .map(KnowledgeSpaceId::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    RetrievalObservationPayload readPayload(
            RetrievalObservationStage stage,
            String payloadType,
            String value
    ) {
        Class<? extends RetrievalObservationPayload> type = payloadClass(stage);
        if (!type.getSimpleName().equals(payloadType)) {
            throw new IllegalStateException("stored observation payload type does not match stage");
        }
        return read(value, type);
    }

    List<ObservationIncompleteReason> readIncompleteReasons(String value) {
        return read(value, INCOMPLETE_REASONS);
    }

    List<Long> readLongs(String value) {
        return read(value, LONG_LIST);
    }

    List<VisitedRetrievalConfiguration> readVisitedConfigurations(String value) {
        return read(value, VISITED_CONFIGS);
    }

    MetricFact readFact(String factType, String value) {
        return switch (factType) {
            case "RUNTIME" -> read(value, MetricFact.Runtime.class);
            case "OFFLINE_GOLD" -> read(value, MetricFact.OfflineGold.class);
            default -> throw new IllegalStateException("stored metric fact type is unsupported");
        };
    }

    String sortedIncompleteReasons(Set<ObservationIncompleteReason> values) {
        return json(values.stream().sorted(Comparator.comparing(Enum::name)).toList());
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored retrieval observation JSON is invalid", failure);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored retrieval observation JSON is invalid", failure);
        }
    }

    private static Class<? extends RetrievalObservationPayload> payloadClass(
            RetrievalObservationStage stage
    ) {
        return switch (stage) {
            case EXECUTION_STARTED -> RetrievalObservationPayload.ExecutionStarted.class;
            case SPACE_ROUTING -> RetrievalObservationPayload.SpaceRoutingCompleted.class;
            case CONFIGURATION_RESOLVED -> RetrievalObservationPayload.ConfigurationResolved.class;
            case QUERY_ANALYSIS -> RetrievalObservationPayload.QueryAnalysisCompleted.class;
            case QUERY_PLANNING -> RetrievalObservationPayload.QueryPlanningCompleted.class;
            case RETRIEVAL_PLAN -> RetrievalObservationPayload.RetrievalPlanCompleted.class;
            case RETRIEVAL_BRANCH -> RetrievalObservationPayload.RetrievalBranchCompleted.class;
            case FUSION -> RetrievalObservationPayload.FusionCompleted.class;
            case RERANK -> RetrievalObservationPayload.RerankCompleted.class;
            case COVERAGE_CHECK -> RetrievalObservationPayload.CoverageCheckCompleted.class;
            case CHAIN_NODE_EVALUATED -> RetrievalObservationPayload.ChainNodeEvaluated.class;
            case CHAIN_NODE_COMPLETED -> RetrievalObservationPayload.ChainNodeCompleted.class;
            case SPACE_CHANGED -> RetrievalObservationPayload.SpaceChanged.class;
            case EVIDENCE_BUILD -> RetrievalObservationPayload.EvidenceBuildCompleted.class;
            case EXECUTION_TERMINAL -> RetrievalObservationPayload.ExecutionTerminal.class;
            case STAGE_FAILURE -> RetrievalObservationPayload.StageFailed.class;
        };
    }
}
