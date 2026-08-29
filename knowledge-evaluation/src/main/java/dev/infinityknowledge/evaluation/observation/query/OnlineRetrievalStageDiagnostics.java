package dev.infinityknowledge.evaluation.observation.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 在线检索逐层诊断汇总，不携带查询文本、候选正文或模型原始响应。 */
public record OnlineRetrievalStageDiagnostics(
        Instant from,
        Instant to,
        List<StageRow> stageRows,
        List<BranchRow> branchRows,
        FusionSummary fusion,
        RerankSummary rerank,
        CoverageSummary coverage,
        List<ChainRow> chainRows
) {
    /** 复制各行并验证固定阶段摘要。 */
    public OnlineRetrievalStageDiagnostics {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        stageRows = List.copyOf(Objects.requireNonNull(stageRows, "stageRows must not be null"));
        branchRows = List.copyOf(Objects.requireNonNull(
                branchRows,
                "branchRows must not be null"
        ));
        Objects.requireNonNull(fusion, "fusion must not be null");
        Objects.requireNonNull(rerank, "rerank must not be null");
        Objects.requireNonNull(coverage, "coverage must not be null");
        chainRows = List.copyOf(Objects.requireNonNull(chainRows, "chainRows must not be null"));
    }

    /** 通用阶段的吞吐、技术成功率、耗时及输入输出规模。 */
    public record StageRow(
            String stage,
            long executionCount,
            long eventCount,
            Integer metricDefinitionVersion,
            OnlineRetrievalOverview.Rate successRate,
            OnlineRetrievalOverview.Percentiles latencyMillis,
            Double averageInputCount,
            Double averageOutputCount
    ) {
        public StageRow {
            stage = required(stage, "stage");
            counts(executionCount, eventCount);
            if (metricDefinitionVersion != null && metricDefinitionVersion < 1) {
                throw new IllegalArgumentException("metricDefinitionVersion must be positive");
            }
            Objects.requireNonNull(successRate, "successRate must not be null");
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
            average(averageInputCount, "averageInputCount");
            average(averageOutputCount, "averageOutputCount");
        }
    }

    /** 单种 Retriever 分支合同的运行汇总。 */
    public record BranchRow(
            String strategy,
            String channel,
            String componentModel,
            String dataIndexVersion,
            long executionCount,
            long eventCount,
            OnlineRetrievalOverview.Rate successRate,
            OnlineRetrievalOverview.Rate emptyRate,
            Double averageCandidateCount,
            OnlineRetrievalOverview.Percentiles latencyMillis
    ) {
        public BranchRow {
            strategy = required(strategy, "strategy");
            channel = required(channel, "channel");
            componentModel = required(componentModel, "componentModel");
            dataIndexVersion = required(dataIndexVersion, "dataIndexVersion");
            counts(executionCount, eventCount);
            Objects.requireNonNull(successRate, "successRate must not be null");
            Objects.requireNonNull(emptyRate, "emptyRate must not be null");
            average(averageCandidateCount, "averageCandidateCount");
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
        }
    }

    /** RRF 融合阶段汇总。 */
    public record FusionSummary(
            long executionCount,
            OnlineRetrievalOverview.Rate duplicateRate,
            Double averageInputCandidateCount,
            Double averageUniqueCandidateCount,
            OnlineRetrievalOverview.Percentiles latencyMillis
    ) {
        public FusionSummary {
            OnlineRetrievalOverview.requireCount(executionCount, "executionCount");
            Objects.requireNonNull(duplicateRate, "duplicateRate must not be null");
            average(averageInputCandidateCount, "averageInputCandidateCount");
            average(averageUniqueCandidateCount, "averageUniqueCandidateCount");
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
        }
    }

    /** 模型或确定性 Reranker 运行汇总。 */
    public record RerankSummary(
            long executionCount,
            OnlineRetrievalOverview.Rate executedRate,
            OnlineRetrievalOverview.Rate fallbackRate,
            Double averageInputCandidateCount,
            Double averageOutputCandidateCount,
            long modelRequestCount,
            OnlineRetrievalOverview.Percentiles latencyMillis
    ) {
        public RerankSummary {
            OnlineRetrievalOverview.requireCount(executionCount, "executionCount");
            OnlineRetrievalOverview.requireCount(modelRequestCount, "modelRequestCount");
            Objects.requireNonNull(executedRate, "executedRate must not be null");
            Objects.requireNonNull(fallbackRate, "fallbackRate must not be null");
            average(averageInputCandidateCount, "averageInputCandidateCount");
            average(averageOutputCandidateCount, "averageOutputCandidateCount");
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
        }
    }

    /** Coverage Judge 的在线代理质量和运行汇总。 */
    public record CoverageSummary(
            long executionCount,
            long checkCount,
            long measuredCount,
            OnlineRetrievalOverview.Rate sufficientRate,
            Double averageScore,
            Double averageRetainedCandidateCount,
            long modelRequestCount,
            OnlineRetrievalOverview.Percentiles latencyMillis
    ) {
        public CoverageSummary {
            OnlineRetrievalOverview.requireCount(executionCount, "executionCount");
            OnlineRetrievalOverview.requireCount(checkCount, "checkCount");
            OnlineRetrievalOverview.requireCount(measuredCount, "measuredCount");
            OnlineRetrievalOverview.requireCount(modelRequestCount, "modelRequestCount");
            Objects.requireNonNull(sufficientRate, "sufficientRate must not be null");
            average(averageScore, "averageScore");
            average(averageRetainedCandidateCount, "averageRetainedCandidateCount");
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
        }
    }

    /** 单个优化链节点的覆盖增益、模型成本与耗时。 */
    public record ChainRow(
            String node,
            String strategy,
            long executionCount,
            long eventCount,
            OnlineRetrievalOverview.Rate positiveGainRate,
            Double averageCoverageDelta,
            long modelRequestCount,
            OnlineRetrievalOverview.Percentiles latencyMillis
    ) {
        public ChainRow {
            node = required(node, "node");
            strategy = required(strategy, "strategy");
            counts(executionCount, eventCount);
            OnlineRetrievalOverview.requireCount(modelRequestCount, "modelRequestCount");
            Objects.requireNonNull(positiveGainRate, "positiveGainRate must not be null");
            if (averageCoverageDelta != null && !Double.isFinite(averageCoverageDelta)) {
                throw new IllegalArgumentException("averageCoverageDelta must be finite");
            }
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
        }
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void counts(long executionCount, long eventCount) {
        OnlineRetrievalOverview.requireCount(executionCount, "executionCount");
        OnlineRetrievalOverview.requireCount(eventCount, "eventCount");
        if (executionCount > eventCount) {
            throw new IllegalArgumentException("executionCount must not exceed eventCount");
        }
    }

    private static void average(Double value, String field) {
        OnlineRetrievalOverview.validateFiniteNonNegative(value, field);
    }
}
