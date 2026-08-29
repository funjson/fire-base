package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 指标事实允许使用的有限维度集合。
 *
 * <p>requestId、traceId、查询指纹、Chunk 和 Document 标识不属于指标维度，
 * 只能留在原始事件中做受权下钻，避免写入时序系统造成高基数。</p>
 */
public record MetricDimensions(Map<Key, String> values) {
    /** execution 尚未收到终态时使用的显式未知值，不能按失败或成功参与计算。 */
    public static final String UNOBSERVED_TECHNICAL_STATUS = "UNOBSERVED";

    /** 指标体系已批准的维度键；不得使用任意字符串扩展标签。 */
    public enum Key {
        SPACE,
        QUERY_CASE,
        CONFIG,
        STRATEGY,
        ATTEMPT,
        VISIT_INDEX,
        STAGE,
        CHANNEL,
        CHAIN_NODE,
        COMPONENT_MODEL,
        DATA_INDEX_VERSION,
        COVERAGE_STATUS,
        TECHNICAL_STATUS,
        TERMINAL_STATUS,
        /**
         * PostgreSQL 旧物化列的兼容键，值始终等于 TECHNICAL_STATUS。
         * 新的统计定义不得再把业务终态写入此键。
         */
        STATUS,
        STOP_REASON,
        PURPOSE,
        TIME_SLICE
    }

    /** 时间窗口粒度进入维度值，防止不同窗口被错误聚合。 */
    public enum TimeSliceGranularity {
        MINUTE(ChronoUnit.MINUTES),
        HOUR(ChronoUnit.HOURS),
        DAY(ChronoUnit.DAYS);

        private final ChronoUnit unit;

        TimeSliceGranularity(ChronoUnit unit) {
            this.unit = unit;
        }

        private Instant truncate(Instant value) {
            return value.truncatedTo(unit);
        }
    }

    /** 复制并校验所有维度；配置、技术状态、用途和时间切片为每条事实的必需项。 */
    public MetricDimensions {
        Objects.requireNonNull(values, "values must not be null");
        EnumMap<Key, String> normalizedInput = new EnumMap<>(Key.class);
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "metric dimension key must not be null");
            normalizedInput.put(key, value);
        });
        normalizeLegacyStatus(normalizedInput);
        EnumMap<Key, String> validated = new EnumMap<>(Key.class);
        normalizedInput.forEach((key, value) -> {
            validated.put(key, validateValue(key, value));
        });
        for (Key required : new Key[] {
                Key.CONFIG, Key.TECHNICAL_STATUS, Key.STATUS,
                Key.PURPOSE, Key.TIME_SLICE
        }) {
            if (!validated.containsKey(required)) {
                throw new IllegalArgumentException(
                        "metric dimensions must contain " + required
                );
            }
        }
        if (!validated.get(Key.TECHNICAL_STATUS).equals(validated.get(Key.STATUS))) {
            throw new IllegalArgumentException(
                    "STATUS compatibility value must match TECHNICAL_STATUS"
            );
        }
        values = Map.copyOf(validated);
    }

    /**
     * 读取指标定义 v1-v3 的 JSON 时，只修复历史上复用 STATUS 的已知形态。
     *
     * <p>旧阶段事实的 STATUS 是技术状态，可直接复制；旧终态事实的 STATUS 是业务终态，
     * 因协议没有保存独立技术状态，只能迁入 TERMINAL_STATUS 并明确标为 UNOBSERVED。
     * 这不是成功或失败推断，历史数据若需要精确技术状态仍应从原始事件重新投影。</p>
     */
    private static void normalizeLegacyStatus(EnumMap<Key, String> values) {
        String legacy = values.get(Key.STATUS);
        String technical = values.get(Key.TECHNICAL_STATUS);
        if (technical != null) {
            if (legacy == null) {
                values.put(Key.STATUS, technical);
            } else if (isTerminalStatus(legacy)) {
                putLegacyTerminalStatus(values, legacy);
                values.put(Key.STATUS, technical);
            }
            return;
        }
        if (legacy == null) {
            return;
        }
        if (isTechnicalStatus(legacy)) {
            values.put(Key.TECHNICAL_STATUS, legacy);
            return;
        }
        if (isTerminalStatus(legacy)) {
            putLegacyTerminalStatus(values, legacy);
            values.put(Key.TECHNICAL_STATUS, UNOBSERVED_TECHNICAL_STATUS);
            values.put(Key.STATUS, UNOBSERVED_TECHNICAL_STATUS);
        }
    }

    private static void putLegacyTerminalStatus(
            EnumMap<Key, String> values,
            String terminalStatus
    ) {
        String existing = values.putIfAbsent(Key.TERMINAL_STATUS, terminalStatus);
        if (existing != null && !existing.equals(terminalStatus)) {
            throw new IllegalArgumentException(
                    "legacy STATUS conflicts with TERMINAL_STATUS"
            );
        }
    }

    private static boolean isTechnicalStatus(String value) {
        try {
            RetrievalObservationStatus.valueOf(value);
            return true;
        } catch (IllegalArgumentException missing) {
            return UNOBSERVED_TECHNICAL_STATUS.equals(value);
        }
    }

    private static boolean isTerminalStatus(String value) {
        try {
            RetrievalTerminalStatus.valueOf(value);
            return true;
        } catch (IllegalArgumentException missing) {
            return false;
        }
    }

    /** 创建只接受有限维度键的 Builder。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 返回指定维度是否存在。 */
    public boolean contains(Key key) {
        return values.containsKey(Objects.requireNonNull(key, "key must not be null"));
    }

    /** 返回指定必需维度。 */
    public String require(Key key) {
        String value = values.get(Objects.requireNonNull(key, "key must not be null"));
        if (value == null) {
            throw new IllegalArgumentException("metric dimension is missing " + key);
        }
        return value;
    }

    /**
     * 使用类型化方法构造指标维度，避免调用方拼接任意标签名。
     */
    public static final class Builder {
        private final EnumMap<Key, String> values = new EnumMap<>(Key.class);

        private Builder() {
        }

        /** 增加单个 Space 切片；跨 Space 总体事实可以省略该维度。 */
        public Builder space(KnowledgeSpaceId spaceId) {
            return put(Key.SPACE, Objects.requireNonNull(spaceId, "spaceId must not be null").value());
        }

        /** 绑定离线评测 Case；运行事实禁止使用该维度。 */
        public Builder queryCase(UUID caseId) {
            return put(
                    Key.QUERY_CASE,
                    Objects.requireNonNull(caseId, "caseId must not be null").toString()
            );
        }

        /** 绑定本次有效检索配置指纹。 */
        public Builder config(String fingerprint) {
            String normalized = DomainChecks.requiredText(
                    fingerprint,
                    "config fingerprint",
                    64
            );
            if (RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT.equals(normalized)) {
                return put(Key.CONFIG, normalized);
            }
            normalized = normalized.toLowerCase(Locale.ROOT);
            if (!RetrievalObservation.isResolvedConfigFingerprint(normalized)) {
                throw new IllegalArgumentException(
                        "config fingerprint must be UNRESOLVED or a lowercase SHA-256 value"
                );
            }
            return put(Key.CONFIG, normalized);
        }

        /** 增加稳定策略码。 */
        public Builder strategy(String strategy) {
            return put(Key.STRATEGY, stableCode(strategy, "strategy"));
        }

        /** 增加从零开始的检索尝试索引。 */
        public Builder attempt(int attemptIndex) {
            if (attemptIndex < 0) {
                throw new IllegalArgumentException("attemptIndex must not be negative");
            }
            return put(Key.ATTEMPT, Integer.toString(attemptIndex));
        }

        /** 增加从零开始的 Space 访问索引。 */
        public Builder visitIndex(int visitIndex) {
            if (visitIndex < 0) {
                throw new IllegalArgumentException("visitIndex must not be negative");
            }
            return put(Key.VISIT_INDEX, Integer.toString(visitIndex));
        }

        /** 增加事件所属的稳定执行阶段。 */
        public Builder stage(RetrievalObservationStage stage) {
            return put(
                    Key.STAGE,
                    Objects.requireNonNull(stage, "stage must not be null").name()
            );
        }

        /** 增加物理召回通道；仅分支事实设置。 */
        public Builder channel(RetrievalChannel channel) {
            return put(
                    Key.CHANNEL,
                    Objects.requireNonNull(channel, "channel must not be null").name()
            );
        }

        /** 增加固定优化链节点码。 */
        public Builder chainNode(String node) {
            return put(Key.CHAIN_NODE, stableCode(node, "chainNode"));
        }

        /** 将组件、Provider、模型和实现版本作为一个受控维度。 */
        public Builder componentModel(
                RetrievalObservationPayload.ComponentVersion component
        ) {
            Objects.requireNonNull(component, "component must not be null");
            return put(
                    Key.COMPONENT_MODEL,
                    String.join(
                            "|",
                            component.component(),
                            component.provider(),
                            component.model(),
                            component.version()
                    )
            );
        }

        /** 增加数据索引代际。 */
        public Builder dataIndexVersion(String version) {
            return put(
                    Key.DATA_INDEX_VERSION,
                    DomainChecks.requiredText(version, "dataIndexVersion", 128)
            );
        }

        /** 增加阶段或已收到 execution 终态的技术状态。 */
        public Builder status(RetrievalObservationStatus status) {
            String value = Objects.requireNonNull(status, "status must not be null").name();
            put(Key.TECHNICAL_STATUS, value);
            return put(Key.STATUS, value);
        }

        /**
         * 标记 execution 尚未收到终态；该值只用于请求分母和完整性，不能推断成功率。
         */
        public Builder unobservedTechnicalStatus() {
            put(Key.TECHNICAL_STATUS, UNOBSERVED_TECHNICAL_STATUS);
            return put(Key.STATUS, UNOBSERVED_TECHNICAL_STATUS);
        }

        /** 增加整次检索的低基数业务终态；不覆盖技术状态。 */
        public Builder terminalStatus(RetrievalTerminalStatus terminalStatus) {
            return put(
                    Key.TERMINAL_STATUS,
                    Objects.requireNonNull(
                            terminalStatus,
                            "terminalStatus must not be null"
                    ).name()
            );
        }

        /** 增加 Coverage Judge 本轮给出的有限状态。 */
        public Builder coverageStatus(
                RetrievalObservationPayload.CoverageTerminalStatus status
        ) {
            return put(
                    Key.COVERAGE_STATUS,
                    Objects.requireNonNull(status, "coverage status must not be null").name()
            );
        }

        /** 增加整次检索的低基数停止原因；非终态层不设置。 */
        public Builder stopReason(RetrievalStopReason stopReason) {
            return put(
                    Key.STOP_REASON,
                    Objects.requireNonNull(stopReason, "stopReason must not be null").name()
            );
        }

        /** 增加在线、评测、测试或回放用途。 */
        public Builder purpose(RetrievalObservationPurpose purpose) {
            return put(
                    Key.PURPOSE,
                    Objects.requireNonNull(purpose, "purpose must not be null").name()
            );
        }

        /** 按显式粒度规范化事件时间切片。 */
        public Builder timeSlice(Instant instant, TimeSliceGranularity granularity) {
            Objects.requireNonNull(instant, "instant must not be null");
            Objects.requireNonNull(granularity, "granularity must not be null");
            return put(
                    Key.TIME_SLICE,
                    granularity.name() + ":" + granularity.truncate(instant)
            );
        }

        /** 完成不可变维度对象。 */
        public MetricDimensions build() {
            return new MetricDimensions(values);
        }

        private Builder put(Key key, String value) {
            values.put(key, safeValue(value, key.name()));
            return this;
        }
    }

    private static String stableCode(String value, String field) {
        String normalized = DomainChecks.requiredText(value, field, 64)
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a stable upper-case code");
        }
        return normalized;
    }

    private static String safeValue(String value, String field) {
        String normalized = DomainChecks.requiredText(value, field, 512);
        if (normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return normalized;
    }

    private static String validateValue(Key key, String value) {
        String normalized = safeValue(value, key.name());
        switch (key) {
            case QUERY_CASE -> UUID.fromString(normalized);
            case CONFIG -> {
                if (!RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT.equals(normalized)
                        && !RetrievalObservation.isResolvedConfigFingerprint(normalized)) {
                    throw new IllegalArgumentException(
                            "CONFIG must be UNRESOLVED or a lowercase SHA-256 value"
                    );
                }
            }
            case STRATEGY -> normalized = stableCode(normalized, "STRATEGY");
            case CHAIN_NODE -> normalized = stableCode(normalized, "CHAIN_NODE");
            case ATTEMPT, VISIT_INDEX -> validateNonNegativeInteger(key, normalized);
            case STAGE -> RetrievalObservationStage.valueOf(normalized);
            case CHANNEL -> RetrievalChannel.valueOf(normalized);
            case COVERAGE_STATUS ->
                    RetrievalObservationPayload.CoverageTerminalStatus.valueOf(normalized);
            case TECHNICAL_STATUS, STATUS -> validateTechnicalStatus(normalized);
            case TERMINAL_STATUS -> RetrievalTerminalStatus.valueOf(normalized);
            case STOP_REASON -> RetrievalStopReason.valueOf(normalized);
            case PURPOSE -> RetrievalObservationPurpose.valueOf(normalized);
            case TIME_SLICE -> validateTimeSlice(normalized);
            case SPACE, COMPONENT_MODEL, DATA_INDEX_VERSION -> {
                // 这些值来自已校验领域标识或组件合同，只需执行通用安全文本校验。
            }
        }
        return normalized;
    }

    private static void validateTimeSlice(String value) {
        int separator = value.indexOf(':');
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalArgumentException("TIME_SLICE has an invalid format");
        }
        TimeSliceGranularity granularity = TimeSliceGranularity.valueOf(
                value.substring(0, separator)
        );
        Instant instant = Instant.parse(value.substring(separator + 1));
        if (!granularity.truncate(instant).equals(instant)) {
            throw new IllegalArgumentException(
                    "TIME_SLICE instant must align with its granularity"
            );
        }
    }

    private static void validateNonNegativeInteger(Key key, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
    }

    /** 技术终态未知是明确状态，不允许用业务终态或任意文本占位。 */
    private static void validateTechnicalStatus(String value) {
        if (!UNOBSERVED_TECHNICAL_STATUS.equals(value)) {
            RetrievalObservationStatus.valueOf(value);
        }
    }
}
