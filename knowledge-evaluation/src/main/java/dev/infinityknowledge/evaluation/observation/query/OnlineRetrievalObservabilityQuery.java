package dev.infinityknowledge.evaluation.observation.query;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在线检索观测的安全、低基数查询条件。
 *
 * <p>用途由读端固定为 ONLINE，调用方不能覆盖。授权 Space 与用户筛选 Space 分开保存，
 * PostgreSQL 等适配器必须先按授权集合排除跨 Space 执行，再应用可选筛选，不能查询后过滤。
 * Space 筛选表示“实际访问过该 Space 的整次执行集合”；跨 Space 执行可出现在多个 Space
 * 面板，因此不同 Space 的请求数不能相加。</p>
 */
public record OnlineRetrievalObservabilityQuery(
        TenantId tenantId,
        Set<KnowledgeSpaceId> authorizedSpaceIds,
        Optional<KnowledgeSpaceId> spaceId,
        Instant from,
        Instant to,
        Instant maturityCutoff,
        Optional<String> configFingerprint,
        Optional<String> dataIndexVersion
) {
    /** 在线页面单次查询最多覆盖三十一天，长期趋势应读取后续汇总表。 */
    public static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);

    /** 复制并校验租户、授权范围、时间窗和有限筛选。 */
    public OnlineRetrievalObservabilityQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        authorizedSpaceIds = Set.copyOf(Objects.requireNonNull(
                authorizedSpaceIds,
                "authorizedSpaceIds must not be null"
        ));
        if (authorizedSpaceIds.isEmpty()) {
            throw new IllegalArgumentException("authorizedSpaceIds must not be empty");
        }
        spaceId = Objects.requireNonNull(spaceId, "spaceId must not be null");
        if (spaceId.isPresent() && !authorizedSpaceIds.contains(spaceId.orElseThrow())) {
            throw new IllegalArgumentException("spaceId is outside the authorized scope");
        }
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must precede to");
        }
        if (Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0) {
            throw new IllegalArgumentException("online observability window exceeds 31 days");
        }
        Objects.requireNonNull(maturityCutoff, "maturityCutoff must not be null");
        if (maturityCutoff.isAfter(to)) {
            throw new IllegalArgumentException("maturityCutoff must not follow to");
        }
        configFingerprint = Objects.requireNonNull(
                configFingerprint,
                "configFingerprint must not be null"
        ).map(OnlineRetrievalObservabilityQuery::validatedConfigFingerprint);
        dataIndexVersion = Objects.requireNonNull(
                dataIndexVersion,
                "dataIndexVersion must not be null"
        ).map(value -> DomainChecks.requiredText(value, "dataIndexVersion", 128));
    }

    /** 根据窗口选择固定时间桶，防止前端任意粒度产生高基数。 */
    public TimeBucketGranularity granularity() {
        Duration window = Duration.between(from, to);
        if (window.compareTo(Duration.ofHours(6)) <= 0) {
            return TimeBucketGranularity.MINUTE;
        }
        if (window.compareTo(Duration.ofDays(14)) <= 0) {
            return TimeBucketGranularity.HOUR;
        }
        return TimeBucketGranularity.DAY;
    }

    private static String validatedConfigFingerprint(String value) {
        String normalized = DomainChecks.requiredText(value, "configFingerprint", 64);
        if (RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT.equals(normalized)) {
            return normalized;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!RetrievalObservation.isResolvedConfigFingerprint(normalized)) {
            throw new IllegalArgumentException(
                    "configFingerprint must be UNRESOLVED or a lowercase SHA-256 value"
            );
        }
        return normalized;
    }

    /** 页面允许的固定时间桶。 */
    public enum TimeBucketGranularity {
        MINUTE,
        HOUR,
        DAY
    }
}
