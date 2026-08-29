package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 由原始检索事件计算出的版本化指标事实。
 *
 * <p>运行事实与依赖 Gold 标注的离线质量事实使用不同 record，调用方无法把无标注的
 * 在线代理分数伪装成真实质量指标。窗口聚合属于查询或外部可观测适配器职责。</p>
 */
public sealed interface MetricFact permits MetricFact.Runtime,
        MetricFact.OfflineGold {

    UUID sourceEventId();

    UUID executionId();

    TenantId tenantId();

    String metricKey();

    int metricDefinitionVersion();

    Aggregation aggregation();

    double value();

    MetricDimensions dimensions();

    Instant observedAt();

    /** 指标事实允许的聚合口径。 */
    enum Aggregation {
        COUNT,
        SUM,
        DISTRIBUTION,
        MACRO_AVERAGE,
        RATIO_NUMERATOR,
        RATIO_DENOMINATOR
    }

    /**
     * 不依赖人工或 Gold 标注的在线运行与诊断事实。
     */
    record Runtime(
            UUID sourceEventId,
            UUID executionId,
            TenantId tenantId,
            String metricKey,
            int metricDefinitionVersion,
            Aggregation aggregation,
            double value,
            MetricDimensions dimensions,
            Instant observedAt
    ) implements MetricFact {
        public Runtime {
            metricKey = validateCommon(
                    sourceEventId,
                    executionId,
                    tenantId,
                    metricKey,
                    metricDefinitionVersion,
                    aggregation,
                    value,
                    dimensions,
                    observedAt
            );
            if (dimensions.contains(MetricDimensions.Key.QUERY_CASE)) {
                throw new IllegalArgumentException(
                        "runtime metric facts must not contain QUERY_CASE"
                );
            }
        }
    }

    /**
     * 明确绑定 Dataset、Case 和标注版本的离线 Gold 质量事实。
     */
    record OfflineGold(
            UUID sourceEventId,
            UUID executionId,
            TenantId tenantId,
            UUID datasetId,
            long datasetVersion,
            UUID caseId,
            String metricKey,
            int metricDefinitionVersion,
            Aggregation aggregation,
            double value,
            MetricDimensions dimensions,
            Instant observedAt
    ) implements MetricFact {
        public OfflineGold {
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            if (datasetVersion < 1L) {
                throw new IllegalArgumentException("datasetVersion must be positive");
            }
            Objects.requireNonNull(caseId, "caseId must not be null");
            metricKey = validateCommon(
                    sourceEventId,
                    executionId,
                    tenantId,
                    metricKey,
                    metricDefinitionVersion,
                    aggregation,
                    value,
                    dimensions,
                    observedAt
            );
            String dimensionCase = dimensions.require(MetricDimensions.Key.QUERY_CASE);
            if (!caseId.toString().equals(dimensionCase)) {
                throw new IllegalArgumentException(
                        "offline Gold fact caseId must match QUERY_CASE dimension"
                );
            }
            RetrievalObservationPurpose purpose = RetrievalObservationPurpose.valueOf(
                    dimensions.require(MetricDimensions.Key.PURPOSE)
            );
            if (purpose == RetrievalObservationPurpose.ONLINE
                    || purpose == RetrievalObservationPurpose.SHADOW) {
                throw new IllegalArgumentException(
                        "offline Gold facts require evaluation, test or replay purpose"
                );
            }
        }
    }

    private static String validateCommon(
            UUID sourceEventId,
            UUID executionId,
            TenantId tenantId,
            String metricKey,
            int metricDefinitionVersion,
            Aggregation aggregation,
            double value,
            MetricDimensions dimensions,
            Instant observedAt
    ) {
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String normalizedKey = DomainChecks.requiredText(metricKey, "metricKey", 128)
                .toLowerCase(Locale.ROOT);
        if (!normalizedKey.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("metricKey must be a stable lower-case key");
        }
        if (metricDefinitionVersion < 1) {
            throw new IllegalArgumentException(
                    "metricDefinitionVersion must be positive"
            );
        }
        Objects.requireNonNull(aggregation, "aggregation must not be null");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("metric value must be finite");
        }
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        return normalizedKey;
    }
}
