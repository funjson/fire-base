package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalStageDiagnostics;

import java.time.Instant;
import java.util.List;

/** 在线检索分层诊断 HTTP 视图。 */
public record StageDiagnosticsView(
        Instant from,
        Instant to,
        List<StageRowView> stageRows,
        List<BranchRowView> branchRows,
        FusionView fusion,
        RerankView rerank,
        CoverageView coverage,
        List<ChainRowView> chainRows
) {
    /** 从已授权读模型创建视图。 */
    public static StageDiagnosticsView from(OnlineRetrievalStageDiagnostics value) {
        return new StageDiagnosticsView(
                value.from(),
                value.to(),
                value.stageRows().stream().map(StageRowView::from).toList(),
                value.branchRows().stream().map(BranchRowView::from).toList(),
                FusionView.from(value.fusion()),
                RerankView.from(value.rerank()),
                CoverageView.from(value.coverage()),
                value.chainRows().stream().map(ChainRowView::from).toList()
        );
    }

    /** 通用阶段行。 */
    public record StageRowView(
            String stage,
            long executionCount,
            long eventCount,
            Integer metricDefinitionVersion,
            OnlineOverviewView.RateView successRate,
            OnlineOverviewView.PercentilesView latencyMillis,
            Double averageInputCount,
            Double averageOutputCount
    ) {
        static StageRowView from(OnlineRetrievalStageDiagnostics.StageRow value) {
            return new StageRowView(
                    value.stage(),
                    value.executionCount(),
                    value.eventCount(),
                    value.metricDefinitionVersion(),
                    OnlineOverviewView.RateView.from(value.successRate()),
                    OnlineOverviewView.PercentilesView.from(value.latencyMillis()),
                    value.averageInputCount(),
                    value.averageOutputCount()
            );
        }
    }

    /** 单个 Retriever 物理分支合同。 */
    public record BranchRowView(
            String strategy,
            String channel,
            String componentModel,
            String dataIndexVersion,
            long executionCount,
            long eventCount,
            OnlineOverviewView.RateView successRate,
            OnlineOverviewView.RateView emptyRate,
            Double averageCandidateCount,
            OnlineOverviewView.PercentilesView latencyMillis
    ) {
        static BranchRowView from(OnlineRetrievalStageDiagnostics.BranchRow value) {
            return new BranchRowView(
                    value.strategy(),
                    value.channel(),
                    value.componentModel(),
                    value.dataIndexVersion(),
                    value.executionCount(),
                    value.eventCount(),
                    OnlineOverviewView.RateView.from(value.successRate()),
                    OnlineOverviewView.RateView.from(value.emptyRate()),
                    value.averageCandidateCount(),
                    OnlineOverviewView.PercentilesView.from(value.latencyMillis())
            );
        }
    }

    /** RRF 融合摘要。 */
    public record FusionView(
            long executionCount,
            OnlineOverviewView.RateView duplicateRate,
            Double averageInputCandidateCount,
            Double averageUniqueCandidateCount,
            OnlineOverviewView.PercentilesView latencyMillis
    ) {
        static FusionView from(OnlineRetrievalStageDiagnostics.FusionSummary value) {
            return new FusionView(
                    value.executionCount(),
                    OnlineOverviewView.RateView.from(value.duplicateRate()),
                    value.averageInputCandidateCount(),
                    value.averageUniqueCandidateCount(),
                    OnlineOverviewView.PercentilesView.from(value.latencyMillis())
            );
        }
    }

    /** Reranker 摘要。 */
    public record RerankView(
            long executionCount,
            OnlineOverviewView.RateView executedRate,
            OnlineOverviewView.RateView fallbackRate,
            Double averageInputCandidateCount,
            Double averageOutputCandidateCount,
            long modelRequestCount,
            OnlineOverviewView.PercentilesView latencyMillis
    ) {
        static RerankView from(OnlineRetrievalStageDiagnostics.RerankSummary value) {
            return new RerankView(
                    value.executionCount(),
                    OnlineOverviewView.RateView.from(value.executedRate()),
                    OnlineOverviewView.RateView.from(value.fallbackRate()),
                    value.averageInputCandidateCount(),
                    value.averageOutputCandidateCount(),
                    value.modelRequestCount(),
                    OnlineOverviewView.PercentilesView.from(value.latencyMillis())
            );
        }
    }

    /** Coverage 在线代理质量和运行摘要。 */
    public record CoverageView(
            long executionCount,
            long checkCount,
            long measuredCount,
            OnlineOverviewView.RateView sufficientRate,
            Double averageScore,
            Double averageRetainedCandidateCount,
            long modelRequestCount,
            OnlineOverviewView.PercentilesView latencyMillis
    ) {
        static CoverageView from(OnlineRetrievalStageDiagnostics.CoverageSummary value) {
            return new CoverageView(
                    value.executionCount(),
                    value.checkCount(),
                    value.measuredCount(),
                    OnlineOverviewView.RateView.from(value.sufficientRate()),
                    value.averageScore(),
                    value.averageRetainedCandidateCount(),
                    value.modelRequestCount(),
                    OnlineOverviewView.PercentilesView.from(value.latencyMillis())
            );
        }
    }

    /** 单个优化链节点摘要。 */
    public record ChainRowView(
            String node,
            String strategy,
            long executionCount,
            long eventCount,
            OnlineOverviewView.RateView positiveGainRate,
            Double averageCoverageDelta,
            long modelRequestCount,
            OnlineOverviewView.PercentilesView latencyMillis
    ) {
        static ChainRowView from(OnlineRetrievalStageDiagnostics.ChainRow value) {
            return new ChainRowView(
                    value.node(),
                    value.strategy(),
                    value.executionCount(),
                    value.eventCount(),
                    OnlineOverviewView.RateView.from(value.positiveGainRate()),
                    value.averageCoverageDelta(),
                    value.modelRequestCount(),
                    OnlineOverviewView.PercentilesView.from(value.latencyMillis())
            );
        }
    }
}
