package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;

import java.time.Instant;
import java.util.List;

/** 在线检索总览 HTTP 视图，比例始终携带分子分母，分位数始终携带样本数。 */
public record OnlineOverviewView(
        Instant from,
        Instant to,
        OnlineRetrievalObservabilityQuery.TimeBucketGranularity granularity,
        long requestCount,
        List<DimensionCountView> configFingerprints,
        List<DimensionCountView> dataIndexVersions,
        RateView technicalSuccessRate,
        RateView degradedRate,
        PercentilesView endToEndLatencyMillis,
        RateView terminalObservationRate,
        RateView firstCoverageSufficientRate,
        RateView finalCoverageSufficientRate,
        RateView coverageRecoveryRate,
        RateView budgetExhaustedRate,
        RateView observationCompleteRate,
        List<PointView> series
) {
    /** 从已授权读模型创建视图。 */
    public static OnlineOverviewView from(OnlineRetrievalOverview value) {
        return new OnlineOverviewView(
                value.from(),
                value.to(),
                value.granularity(),
                value.requestCount(),
                value.configFingerprints().stream().map(DimensionCountView::from).toList(),
                value.dataIndexVersions().stream().map(DimensionCountView::from).toList(),
                RateView.from(value.technicalSuccessRate()),
                RateView.from(value.degradedRate()),
                PercentilesView.from(value.endToEndLatencyMillis()),
                RateView.from(value.terminalObservationRate()),
                RateView.from(value.firstCoverageSufficientRate()),
                RateView.from(value.finalCoverageSufficientRate()),
                RateView.from(value.coverageRecoveryRate()),
                RateView.from(value.budgetExhaustedRate()),
                RateView.from(value.observationCompleteRate()),
                value.series().stream().map(PointView::from).toList()
        );
    }

    /** 一个配置或索引代际覆盖的去重 execution 数。 */
    public record DimensionCountView(String value, long count) {
        static DimensionCountView from(OnlineRetrievalOverview.DimensionCount value) {
            return new DimensionCountView(value.value(), value.count());
        }
    }

    /** 带分子分母的比例；无有效分母时 value 为 null。 */
    public record RateView(long numerator, long denominator, Double value) {
        static RateView from(OnlineRetrievalOverview.Rate value) {
            return new RateView(value.numerator(), value.denominator(), value.value());
        }
    }

    /** 带样本量的耗时分布；无样本时分位值为 null。 */
    public record PercentilesView(long sampleCount, Double p50, Double p95, Double p99) {
        static PercentilesView from(OnlineRetrievalOverview.Percentiles value) {
            return new PercentilesView(
                    value.sampleCount(),
                    value.p50(),
                    value.p95(),
                    value.p99()
            );
        }
    }

    /** 固定 UTC 时间桶趋势点。 */
    public record PointView(
            Instant bucketStart,
            long requestCount,
            RateView technicalSuccessRate,
            RateView degradedRate,
            PercentilesView latencyMillis,
            RateView terminalObservationRate,
            RateView firstCoverageSufficientRate,
            RateView finalCoverageSufficientRate,
            RateView observationCompleteRate
    ) {
        static PointView from(OnlineRetrievalOverview.Point value) {
            return new PointView(
                    value.bucketStart(),
                    value.requestCount(),
                    RateView.from(value.technicalSuccessRate()),
                    RateView.from(value.degradedRate()),
                    PercentilesView.from(value.latencyMillis()),
                    RateView.from(value.terminalObservationRate()),
                    RateView.from(value.firstCoverageSufficientRate()),
                    RateView.from(value.finalCoverageSufficientRate()),
                    RateView.from(value.observationCompleteRate())
            );
        }
    }
}
