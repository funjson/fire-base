package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalTextFingerprinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * 为一次检索执行生成连续、强类型且不含正文的逐层观测事件。
 *
 * <p>发布属于旁路能力：发布失败会留下稳定警告并继续业务执行。序号仍然递增，
 * 使消费端能够识别事件缺口，而不是把不完整执行误判为完整数据。</p>
 */
final class RetrievalObservationEmitter {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            RetrievalObservationEmitter.class
    );
    private final RetrievalObservationPublisher publisher;
    private final RetrievalTextFingerprinter fingerprinter;
    private final Clock clock;
    private final KnowledgeQuery query;
    private final UUID executionId;
    private final List<String> warnings;
    private long nextSequence;
    private Set<KnowledgeSpaceId> currentSpaceIds = Set.of();
    private int visitIndex;
    private int attemptIndex;
    private String configFingerprint = RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT;
    private String activeComponent = "EXECUTION_SETUP";
    private int activeInputCount = 1;
    private Instant activeStageStartedAt;

    RetrievalObservationEmitter(
            RetrievalObservationPublisher publisher,
            RetrievalTextFingerprinter fingerprinter,
            Clock clock,
            KnowledgeQuery query,
            UUID executionId,
            List<String> warnings
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.fingerprinter = Objects.requireNonNull(
                fingerprinter,
                "fingerprinter must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.warnings = Objects.requireNonNull(warnings, "warnings must not be null");
    }

    /** 发布固定为序号零的执行开始事件。 */
    void started(Instant startedAt) {
        activeStageStartedAt = Objects.requireNonNull(
                startedAt,
                "startedAt must not be null"
        );
        publish(
                Set.of(),
                new RetrievalObservationPayload.ExecutionStarted(
                        fingerprint(query.text()),
                        query.topK(),
                        query.spaceIds().size()
                ),
                RetrievalObservationStatus.STARTED,
                "NONE",
                startedAt
        );
    }

    /**
     * 标记下一条正常完成事件所对应的执行层，供未处理异常生成安全的失败层事件。
     *
     * <p>组件码不能来自异常消息或模型输出；输入数量只表示该层收到的业务对象数。</p>
     */
    void beginStage(String component, int inputCount, Instant stageStartedAt) {
        activeComponent = Objects.requireNonNull(component, "component must not be null");
        if (inputCount < 0) {
            throw new IllegalArgumentException("inputCount must not be negative");
        }
        activeInputCount = inputCount;
        activeStageStartedAt = Objects.requireNonNull(
                stageStartedAt,
                "stageStartedAt must not be null"
        );
    }

    /** 在整次执行失败终态之前发布唯一的未处理失败层事件。 */
    void failedActiveStage() {
        Instant failedAt = activeStageStartedAt == null ? clock.instant() : activeStageStartedAt;
        emit(
                new RetrievalObservationPayload.StageFailed(
                        activeComponent,
                        activeInputCount
                ),
                RetrievalObservationStatus.FAILED,
                activeComponent + "_FAILED",
                failedAt
        );
    }

    /** 发布路由层事件；模型只负责排序，载荷保存最终有序 Space 列表。 */
    void routing(
            Set<KnowledgeSpaceId> routedSpaceIds,
            RetrievalObservationPayload.SpaceRoutingCompleted payload,
            RetrievalObservationStatus status,
            String reasonCode,
            Instant stageStartedAt
    ) {
        publish(routedSpaceIds, payload, status, reasonCode, stageStartedAt);
    }

    /** Space 切换事件同时声明前后两个 Space，随后才推进 visit 下标。 */
    void spaceChanged(
            KnowledgeSpaceId fromSpaceId,
            KnowledgeSpaceId toSpaceId,
            RetrievalObservationPayload.SpaceChanged payload,
            Instant stageStartedAt
    ) {
        publish(
                Set.of(fromSpaceId, toSpaceId),
                payload,
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                stageStartedAt
        );
    }

    /** 进入有序列表中的下一次 Space 访问，不创建额外的激活态对象。 */
    void enterVisit(int index, KnowledgeSpaceId spaceId) {
        if (index < 0) {
            throw new IllegalArgumentException("visit index must not be negative");
        }
        visitIndex = index;
        attemptIndex = 0;
        currentSpaceIds = Set.of(Objects.requireNonNull(spaceId, "spaceId must not be null"));
        configFingerprint = RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT;
    }

    /** 记录当前 visit 的物化配置指纹，后续事件不得再回到未解析状态。 */
    void resolvedConfiguration(String fingerprint) {
        if (!RetrievalObservation.isResolvedConfigFingerprint(fingerprint)) {
            throw new IllegalArgumentException("fingerprint must be a resolved SHA-256 value");
        }
        configFingerprint = fingerprint;
    }

    /** 更新当前实际检索尝试索引，索引从零开始。 */
    void attempt(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("attempt index must not be negative");
        }
        attemptIndex = index;
    }

    /** 发布当前 visit 的一个已完成阶段。 */
    void emit(
            RetrievalObservationPayload payload,
            RetrievalObservationStatus status,
            String reasonCode,
            Instant stageStartedAt
    ) {
        publish(currentSpaceIds, payload, status, reasonCode, stageStartedAt);
    }

    /** 只生成不可逆查询指纹，事件协议中不保存查询或变体正文。 */
    RetrievalObservationPayload.TextFingerprint fingerprint(String text) {
        return Objects.requireNonNull(
                fingerprinter.fingerprint(text),
                "fingerprinter must not return null"
        );
    }

    UUID executionId() {
        return executionId;
    }

    private void publish(
            Set<KnowledgeSpaceId> spaceIds,
            RetrievalObservationPayload payload,
            RetrievalObservationStatus status,
            String reasonCode,
            Instant stageStartedAt
    ) {
        Instant completedAt = clock.instant();
        Instant safeStartedAt = completedAt.isBefore(stageStartedAt)
                ? completedAt : stageStartedAt;
        long sequence = nextSequence++;
        RetrievalObservation observation = new RetrievalObservation(
                UUID.randomUUID(),
                executionId,
                query.requestId(),
                sequence,
                query.purpose(),
                query.principal().tenantId(),
                Set.copyOf(spaceIds),
                visitIndex,
                attemptIndex,
                payload.stage(),
                status,
                reasonCode,
                configFingerprint,
                safeStartedAt,
                completedAt,
                RetrievalObservation.CURRENT_SCHEMA_VERSION,
                payload
        );
        try {
            publisher.publish(observation);
        } catch (RuntimeException publishFailure) {
            if (!warnings.contains("OBSERVATION_PUBLISH_FAILED")) {
                warnings.add("OBSERVATION_PUBLISH_FAILED");
            }
            LOGGER.error(
                    "Retrieval observation publish failed: executionId={}, requestId={}, "
                            + "tenantId={}, sequence={}, stage={}, failureDiagnostic={}",
                    executionId,
                    query.requestId(),
                    query.principal().tenantId().value(),
                    sequence,
                    payload.stage(),
                    safeFailureDiagnostic(publishFailure)
            );
        }
    }

    /**
     * 只记录异常类型链和业务代码位置，不记录可能携带 SQL、查询或模型响应的异常消息。
     */
    private static String safeFailureDiagnostic(Throwable failure) {
        StringJoiner diagnostic = new StringJoiner(" <- ");
        Throwable current = Objects.requireNonNull(failure, "failure must not be null");
        int depth = 0;
        while (current != null && depth++ < 8) {
            StackTraceElement location = firstApplicationFrame(current);
            diagnostic.add(current.getClass().getSimpleName()
                    + (location == null ? "" : "@" + location.getClassName()
                    + "." + location.getMethodName() + ":" + location.getLineNumber()));
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return diagnostic.toString();
    }

    private static StackTraceElement firstApplicationFrame(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("dev.infinityknowledge.")) {
                return frame;
            }
        }
        StackTraceElement[] frames = failure.getStackTrace();
        return frames.length == 0 ? null : frames[0];
    }
}
