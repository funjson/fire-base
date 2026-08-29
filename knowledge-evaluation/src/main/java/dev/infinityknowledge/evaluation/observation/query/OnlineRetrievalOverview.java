package dev.infinityknowledge.evaluation.observation.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 在线检索健康、代理质量与观测完整性的窗口汇总。 */
public record OnlineRetrievalOverview(
        Instant from,
        Instant to,
        OnlineRetrievalObservabilityQuery.TimeBucketGranularity granularity,
        long requestCount,
        List<DimensionCount> configFingerprints,
        List<DimensionCount> dataIndexVersions,
        Rate technicalSuccessRate,
        Rate degradedRate,
        Percentiles endToEndLatencyMillis,
        Rate terminalObservationRate,
        Rate firstCoverageSufficientRate,
        Rate finalCoverageSufficientRate,
        Rate coverageRecoveryRate,
        Rate budgetExhaustedRate,
        Rate observationCompleteRate,
        List<Point> series
) {
    /** 复制时间序列并校验公共值对象。 */
    public OnlineRetrievalOverview {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(granularity, "granularity must not be null");
        requireCount(requestCount, "requestCount");
        configFingerprints = List.copyOf(Objects.requireNonNull(
                configFingerprints,
                "configFingerprints must not be null"
        ));
        dataIndexVersions = List.copyOf(Objects.requireNonNull(
                dataIndexVersions,
                "dataIndexVersions must not be null"
        ));
        Objects.requireNonNull(technicalSuccessRate, "technicalSuccessRate must not be null");
        Objects.requireNonNull(degradedRate, "degradedRate must not be null");
        Objects.requireNonNull(endToEndLatencyMillis, "endToEndLatencyMillis must not be null");
        Objects.requireNonNull(terminalObservationRate, "terminalObservationRate must not be null");
        Objects.requireNonNull(
                firstCoverageSufficientRate,
                "firstCoverageSufficientRate must not be null"
        );
        Objects.requireNonNull(
                finalCoverageSufficientRate,
                "finalCoverageSufficientRate must not be null"
        );
        Objects.requireNonNull(coverageRecoveryRate, "coverageRecoveryRate must not be null");
        Objects.requireNonNull(budgetExhaustedRate, "budgetExhaustedRate must not be null");
        Objects.requireNonNull(
                observationCompleteRate,
                "observationCompleteRate must not be null"
        );
        series = List.copyOf(Objects.requireNonNull(series, "series must not be null"));
    }

    /** 一个受控配置或索引代际在窗口内覆盖的 execution 数。 */
    public record DimensionCount(String value, long count) {
        public DimensionCount {
            Objects.requireNonNull(value, "value must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException("dimension value must not be blank");
            }
            requireCount(count, "count");
        }
    }

    /** 带分子、分母的比率；无有效分母时 value 为 null，不使用零伪装无数据。 */
    public record Rate(long numerator, long denominator, Double value) {
        public Rate {
            requireCount(numerator, "numerator");
            requireCount(denominator, "denominator");
            if (numerator > denominator) {
                throw new IllegalArgumentException("rate numerator must not exceed denominator");
            }
            if ((denominator == 0L) != (value == null)) {
                throw new IllegalArgumentException("rate value must match denominator presence");
            }
            if (value != null && (!Double.isFinite(value) || value < 0.0D || value > 1.0D)) {
                throw new IllegalArgumentException("rate value must be between zero and one");
            }
        }

        /** 从两个计数创建不会产生 NaN 的比率。 */
        public static Rate of(long numerator, long denominator) {
            return new Rate(
                    numerator,
                    denominator,
                    denominator == 0L ? null : (double) numerator / denominator
            );
        }
    }

    /** 分布样本数及 P50/P95/P99；无样本时三个分位值均为空。 */
    public record Percentiles(long sampleCount, Double p50, Double p95, Double p99) {
        public Percentiles {
            requireCount(sampleCount, "sampleCount");
            boolean allMissing = p50 == null && p95 == null && p99 == null;
            if ((sampleCount == 0L) != allMissing) {
                throw new IllegalArgumentException(
                        "percentile values must match sample count presence"
                );
            }
            validateFiniteNonNegative(p50, "p50");
            validateFiniteNonNegative(p95, "p95");
            validateFiniteNonNegative(p99, "p99");
            if (p50 != null && (p50 > p95 || p95 > p99)) {
                throw new IllegalArgumentException("percentiles must be monotonic");
            }
        }

        /** 创建无样本分布。 */
        public static Percentiles empty() {
            return new Percentiles(0L, null, null, null);
        }
    }

    /** 单个固定时间桶的趋势点。 */
    public record Point(
            Instant bucketStart,
            long requestCount,
            Rate technicalSuccessRate,
            Rate degradedRate,
            Percentiles latencyMillis,
            Rate terminalObservationRate,
            Rate firstCoverageSufficientRate,
            Rate finalCoverageSufficientRate,
            Rate observationCompleteRate
    ) {
        public Point {
            Objects.requireNonNull(bucketStart, "bucketStart must not be null");
            requireCount(requestCount, "requestCount");
            Objects.requireNonNull(technicalSuccessRate, "technicalSuccessRate must not be null");
            Objects.requireNonNull(degradedRate, "degradedRate must not be null");
            Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
            Objects.requireNonNull(
                    terminalObservationRate,
                    "terminalObservationRate must not be null"
            );
            Objects.requireNonNull(
                    firstCoverageSufficientRate,
                    "firstCoverageSufficientRate must not be null"
            );
            Objects.requireNonNull(
                    finalCoverageSufficientRate,
                    "finalCoverageSufficientRate must not be null"
            );
            Objects.requireNonNull(
                    observationCompleteRate,
                    "observationCompleteRate must not be null"
            );
        }
    }

    static void requireCount(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    static void validateFiniteNonNegative(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value < 0.0D)) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }

    static void validateUnit(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value < 0.0D || value > 1.0D)) {
            throw new IllegalArgumentException(field + " must be between zero and one");
        }
    }
}
