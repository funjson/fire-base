package dev.infinityknowledge.domain.retrieval.observation;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一条可序列化并可通过进程内事件或 MQ 传输的检索执行观测。
 *
 * <p>{@code sequence} 在单次 execution 内从零开始严格递增。开始事件固定为零，
 * 每层结束后发布一条新事件，最终无论成功或失败都发布终态事件。Envelope 不保存
 * 原查询、知识正文或模型原始响应。</p>
 *
 * @param eventId 事件幂等标识，重投时必须保持不变
 * @param executionId 一次实际执行标识，不使用调用方 requestId 代替
 * @param requestId 调用关联标识，不承担事件幂等语义
 * @param sequence execution 内从零开始的连续序号
 * @param purpose 执行用途
 * @param tenantId 租户标识
 * @param spaceIds 当前层实际涉及的 Space；尚未路由时允许为空
 * @param visitIndex Space 访问索引，零表示首次访问
 * @param attemptIndex 检索尝试索引，零表示首次尝试
 * @param stage 执行阶段
 * @param status 稳定技术状态
 * @param reasonCode 稳定原因码；正常状态固定为 NONE
 * @param configFingerprint 当前 visit 的配置 SHA-256 指纹；解析前使用受控哨兵值
 * @param startedAt 本层开始时间；协议统一到微秒精度以保证跨数据库和 MQ 重放相等
 * @param completedAt 本层完成或事件产生时间；协议统一到微秒精度
 * @param schemaVersion 事件协议版本
 * @param payload 与阶段一一对应的强类型载荷
 */
public record RetrievalObservation(
        UUID eventId,
        UUID executionId,
        UUID requestId,
        long sequence,
        RetrievalObservationPurpose purpose,
        TenantId tenantId,
        Set<KnowledgeSpaceId> spaceIds,
        int visitIndex,
        int attemptIndex,
        RetrievalObservationStage stage,
        RetrievalObservationStatus status,
        String reasonCode,
        String configFingerprint,
        Instant startedAt,
        Instant completedAt,
        int schemaVersion,
        RetrievalObservationPayload payload
) {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final long MAX_SEQUENCE = 100_000L;
    /**
     * 配置尚未解析时的唯一受控值。
     *
     * <p>开始事件必须先于配置解析发布，因而不能伪造一个配置指纹。该值只表达
     * “尚未知晓”。成功解析后同一 visit 的后续事件必须携带真实指纹；切换 Space
     * 进入新 visit 后可以重新使用该值。</p>
     */
    public static final String UNRESOLVED_CONFIG_FINGERPRINT = "UNRESOLVED";

    /**
     * 校验事件身份、顺序、阶段载荷、时间和配置版本的不变量。
     */
    public RetrievalObservation {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (sequence < 0L || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("sequence must be between zero and 100000");
        }
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        spaceIds = Set.copyOf(Objects.requireNonNull(spaceIds, "spaceIds must not be null"));
        if (spaceIds.size() > 100) {
            throw new IllegalArgumentException("spaceIds must not exceed 100 entries");
        }
        if (visitIndex < 0 || attemptIndex < 0) {
            throw new IllegalArgumentException(
                    "visitIndex and attemptIndex must not be negative"
            );
        }
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(status, "status must not be null");
        reasonCode = requireReasonCode(reasonCode);
        configFingerprint = normalizeConfigFingerprint(configFingerprint);
        startedAt = canonicalTimestamp(startedAt, "startedAt");
        completedAt = canonicalTimestamp(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.stage() != stage) {
            throw new IllegalArgumentException("payload stage must match envelope stage");
        }
        validateConfigurationPayload(spaceIds, status, payload);
        validateLifecycle(
                sequence,
                stage,
                status,
                reasonCode,
                configFingerprint,
                payload
        );
    }

    /** 返回已规范为非负值的阶段耗时。 */
    public Duration duration() {
        return Duration.between(startedAt, completedAt);
    }

    /** 返回该事件是否为整次执行的终态。 */
    public boolean terminal() {
        return stage == RetrievalObservationStage.EXECUTION_TERMINAL;
    }

    /** 返回该值是否为已解析的 SHA-256 配置指纹。 */
    public static boolean isResolvedConfigFingerprint(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static void validateLifecycle(
            long sequence,
            RetrievalObservationStage stage,
            RetrievalObservationStatus status,
            String reasonCode,
            String configFingerprint,
            RetrievalObservationPayload payload
    ) {
        if (stage == RetrievalObservationStage.EXECUTION_STARTED) {
            if (sequence != 0L || status != RetrievalObservationStatus.STARTED) {
                throw new IllegalArgumentException(
                        "execution started event must use sequence zero and STARTED status"
                );
            }
        } else if (sequence == 0L || status == RetrievalObservationStatus.STARTED) {
            throw new IllegalArgumentException(
                    "only execution started event may use sequence zero or STARTED status"
            );
        }

        if (stage == RetrievalObservationStage.EXECUTION_TERMINAL) {
            if (!status.terminalAllowed()) {
                throw new IllegalArgumentException("invalid execution terminal status");
            }
            var terminalPayload = (RetrievalObservationPayload.ExecutionTerminal) payload;
            boolean technicalFailure = terminalPayload.terminalStatus()
                    == RetrievalTerminalStatus.CHECK_FAILED
                    || terminalPayload.terminalStatus()
                    == RetrievalTerminalStatus.TECHNICAL_FAILED;
            if (technicalFailure != (status == RetrievalObservationStatus.FAILED)) {
                throw new IllegalArgumentException(
                        "terminal event status must match the business technical outcome"
                );
            }
            if (status == RetrievalObservationStatus.SUCCEEDED && terminalPayload.degraded()) {
                throw new IllegalArgumentException(
                        "successful terminal status must not contain degradation reasons"
                );
            }
            if (status == RetrievalObservationStatus.DEGRADED && !terminalPayload.degraded()) {
                throw new IllegalArgumentException(
                        "terminal DEGRADED status must match payload degraded flag"
                );
            }
            if ((status == RetrievalObservationStatus.SUCCEEDED
                    || status == RetrievalObservationStatus.DEGRADED)
                    && !isResolvedConfigFingerprint(configFingerprint)) {
                throw new IllegalArgumentException(
                        "successful or degraded terminal event requires a resolved config fingerprint"
                );
            }
        }

        if (stage == RetrievalObservationStage.STAGE_FAILURE
                && status != RetrievalObservationStatus.FAILED) {
            throw new IllegalArgumentException("stage failure event must use FAILED status");
        }

        if (stage == RetrievalObservationStage.CONFIGURATION_RESOLVED
                && (status == RetrievalObservationStatus.SUCCEEDED
                        || status == RetrievalObservationStatus.DEGRADED)
                && !isResolvedConfigFingerprint(configFingerprint)) {
            throw new IllegalArgumentException(
                    "successful or degraded configuration event requires a resolved config fingerprint"
            );
        }

        boolean normal = status == RetrievalObservationStatus.STARTED
                || status == RetrievalObservationStatus.SUCCEEDED;
        if (normal != "NONE".equals(reasonCode)) {
            throw new IllegalArgumentException(
                    "normal statuses require NONE and non-normal statuses require a reason code"
            );
        }
    }

    private static String requireReasonCode(String value) {
        String normalized = DomainChecks.requiredText(value, "reasonCode", 64)
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("reasonCode must be a stable upper-case code");
        }
        return normalized;
    }

    private static void validateConfigurationPayload(
            Set<KnowledgeSpaceId> spaceIds,
            RetrievalObservationStatus status,
            RetrievalObservationPayload payload
    ) {
        if (!(payload instanceof RetrievalObservationPayload.ConfigurationResolved value)) {
            return;
        }
        if (!spaceIds.equals(Set.of(value.spaceId()))) {
            throw new IllegalArgumentException(
                    "configuration event must contain exactly its resolved spaceId"
            );
        }
        if ((status == RetrievalObservationStatus.SUCCEEDED
                || status == RetrievalObservationStatus.DEGRADED)
                && value.sourceRevision() < 1L) {
            throw new IllegalArgumentException(
                    "successful configuration event requires a positive sourceRevision"
            );
        }
    }

    private static String normalizeConfigFingerprint(String value) {
        String normalized = DomainChecks.requiredText(
                value,
                "configFingerprint",
                64
        );
        if (UNRESOLVED_CONFIG_FINGERPRINT.equals(normalized)) {
            return normalized;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!isResolvedConfigFingerprint(normalized)) {
            throw new IllegalArgumentException(
                    "configFingerprint must be UNRESOLVED or a lowercase SHA-256 value"
            );
        }
        return normalized;
    }

    /**
     * PostgreSQL {@code timestamptz} 只保留微秒；在领域协议入口统一精度，避免持久化重放后
     * 因纳秒尾数丢失而把同一事件误判为不同内容。
     */
    private static Instant canonicalTimestamp(Instant value, String field) {
        return Objects.requireNonNull(value, field + " must not be null")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
