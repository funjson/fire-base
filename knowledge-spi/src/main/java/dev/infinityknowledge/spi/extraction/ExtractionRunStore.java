package dev.infinityknowledge.spi.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 保存统一的多文件抽取任务、原件目录和逐文件结果。
 *
 * <p>该端口只记录任务和抽取产物，不直接提供 Document、Revision 或 Projection
 * 写入能力；TEST_ONLY 与 INGEST 的发布差异由各自 Processor 决定。后台执行只使用
 * {@code workerId + heartbeatAt} 做简单所有权校验，不引入租约令牌或 Fence 对象。</p>
 */
public interface ExtractionRunStore {

    /** 创建一个尚未开放给 Worker 的任务，用于逐文件登记已经写入 OSS 的原件。 */
    RunSnapshot begin(BeginRequest request);

    /** 在任务进入可执行状态前，登记一个 OSS 原件并创建对应的排队 Item。 */
    void appendSource(
            UUID runId,
            SourceAsset sourceAsset,
            PublicationAttributes publication,
            Instant now
    );

    /** 完成 Multipart 接收；只有成功 seal 的任务才能被 Worker 原子领取。 */
    RunSnapshot seal(UUID runId, int expectedItems, Instant now);

    /** Multipart 接收失败时保留已登记的 OSS 原件，并把任务标记为失败。 */
    void failCreation(UUID runId, String errorCode, Instant now);

    /** 查询租户与空间边界内的单个任务。 */
    Optional<RunSnapshot> find(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId
    );

    /** 按创建时间倒序查询空间任务；实现必须执行有界查询。 */
    List<RunSnapshot> list(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            int limit
    );

    /**
     * 原子领取一个已 seal 的排队任务，或接管心跳已经超时的任务。
     *
     * <p>领取取消中的超时任务仅用于完成取消收尾，不会恢复抽取。</p>
     */
    Optional<RunSnapshot> claimNext(
            ExtractionMode mode,
            String workerId,
            Instant staleBefore,
            Instant now
    );

    /**
     * 有界回收超过接收时限且尚未 seal 的任务。
     *
     * <p>只更新 PostgreSQL 状态和 Item，不删除已经写入 OSS 的来源。</p>
     */
    int failInterruptedUploads(Instant createdBefore, Instant now, int limit);

    /** 仅在任务仍由指定 Worker 执行时刷新心跳。 */
    boolean heartbeat(UUID runId, String workerId, Instant now);

    /** 查询当前所有者是否收到取消请求。 */
    boolean cancellationRequested(UUID runId, String workerId);

    /** 把一个排队或被接管的 Item 标记为正在执行。 */
    boolean startItem(UUID runId, UUID itemId, String workerId, Instant now);

    /**
     * 把 INGEST Item 从抽取阶段原子切换为不可取消的正式发布阶段。
     *
     * <p>实现必须先锁定仍由当前 Worker 持有的 RUNNING 任务，再写入
     * {@link ItemStage#PUBLISHING}，从而与取消请求按同一任务行串行化。</p>
     */
    boolean beginPublication(UUID runId, UUID itemId, String workerId, Instant now);

    /** 保存逐文件成功结果；诊断中不得包含原文或模型原始响应。 */
    boolean succeedItem(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemDiagnostics diagnostics,
            ExtractionPreview preview,
            Instant now
    );

    /** 保存 INGEST 的抽取诊断、预览以及已经事务发布的 Document/Revision。 */
    boolean succeedPublishedItem(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemDiagnostics diagnostics,
            ExtractionPreview preview,
            DocumentId documentId,
            UUID revisionId,
            Instant now
    );

    /**
     * 记录 INGEST 的同内容幂等短路结果。
     *
     * <p>重复命中属于成功类终态；正常情况从 PARSE 阶段直接短路。若发布事务已经
     * 提交但任务结果尚未落库，接管者也可以从 PUBLISHING 阶段按既有正式修订收口。
     * 输出标识始终指向已经存在的正式修订，不能伪造新发布。</p>
     */
    boolean skipDuplicateItem(
            UUID runId,
            UUID itemId,
            String workerId,
            DocumentId documentId,
            UUID revisionId,
            Instant now
    );

    /** 保存逐文件稳定错误码，不持久化异常消息。 */
    boolean failItem(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemStage stage,
            String errorCode,
            Instant now
    );

    /**
     * 结束任务；实现必须同时匹配任务 ID、当前状态和 workerId。
     *
     * <p>{@code finalStatus} 只允许成功、失败或取消。真实 Dataset 已执行时，
     * {@code gateReport} 可以与成功或失败 Run 同时保存，两种状态不得互相推断；
     * 取消时尚未结束的 Item 也应一次性进入 CANCELLED，避免 UI 长期显示伪运行状态。</p>
     */
    boolean finishRun(
            UUID runId,
            String workerId,
            RunStatus finalStatus,
            String errorCode,
            ExtractionGateReport gateReport,
            Instant now
    );

    /** 请求协作式取消，并返回数据库中的最新状态。 */
    Optional<RunSnapshot> requestCancel(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId,
            Instant now
    );

    /** 抽取任务状态。 */
    enum RunStatus {
        QUEUED,
        RUNNING,
        CANCEL_REQUESTED,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        /** 返回该状态是否不再发生后台流转。 */
        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    /** 单个文件的执行状态。 */
    enum ItemStatus {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        SKIPPED_DUPLICATE,
        FAILED,
        CANCELLED
    }

    /**
     * 单文件正式发布所需的不可变业务属性。
     *
     * <p>它属于任务 Item，而不是 OSS SourceAsset：同一原件可以在不同业务上下文中
     * 使用不同标题或权限，但来源目录的完整性元数据不能随发布选择变化。</p>
     */
    record PublicationAttributes(
            String externalId,
            String title,
            int authority
    ) {
        /** 限制检索标题、外部幂等键和权限分值的边界。 */
        public PublicationAttributes {
            externalId = requiredText(externalId, "externalId", 512);
            title = requiredText(title, "title", 512);
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
        }
    }

    /**
     * 页面可展示的粗粒度阶段归因。
     *
     * <p>{@code STORED} 表示原件已写入 OSS；当前引擎尚未暴露逐阶段 Observer，
     * 因此运行中的 {@code PARSE} 是“已开始抽取”，不是精确实时进度。失败时仅在
     * 已知稳定异常能够归因时记录 CLEAN/CHUNK，避免页面把推测当成真实阶段。</p>
     */
    enum ItemStage {
        STORED,
        PARSE,
        CLEAN,
        CHUNK,
        PUBLISHING,
        COMPLETED
    }

    /**
     * 已经进入对象存储的不可变原件目录。
     *
     * @param objectId 可传给 ObjectStorage 的逻辑对象标识，不是物理 Bucket/Key
     * @param storageId 对象存储 Adapter 返回的不可解析标识，仅供运维关联
     */
    record SourceAsset(
            UUID id,
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String objectId,
            String storageId,
            String fileName,
            String mediaType,
            long contentLength,
            String checksumSha256,
            Instant storedAt
    ) {

        /** 保存 OSS 返回的完整性元数据。 */
        public SourceAsset {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(objectId, "objectId must not be null");
            Objects.requireNonNull(storageId, "storageId must not be null");
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(checksumSha256, "checksumSha256 must not be null");
            Objects.requireNonNull(storedAt, "storedAt must not be null");
        }
    }

    /**
     * 成功 Item 的非敏感分层指标。
     *
     * <p>基础列继续保存高频查询需要的 Parser、产物数和耗时；清洗去向与 Chunk
     * 边界决策作为强类型明细持久化。该对象禁止承载正文、向量或模型原始响应。</p>
     */
    record ItemDiagnostics(
            String parserId,
            String processorVersion,
            int elementCount,
            int chunkCount,
            long parseDurationMillis,
            long cleanDurationMillis,
            long chunkDurationMillis,
            CleaningDiagnostics cleaning,
            ChunkDiagnostics chunking
    ) {

        /** 诊断只允许标识、计数和耗时。 */
        public ItemDiagnostics {
            Objects.requireNonNull(parserId, "parserId must not be null");
            Objects.requireNonNull(processorVersion, "processorVersion must not be null");
            Objects.requireNonNull(cleaning, "cleaning must not be null");
            Objects.requireNonNull(chunking, "chunking must not be null");
            if (elementCount < 0 || chunkCount < 0
                    || parseDurationMillis < 0 || cleanDurationMillis < 0
                    || chunkDurationMillis < 0) {
                throw new IllegalArgumentException("diagnostic values must be non-negative");
            }
            if (chunkCount != chunking.finalChunkCount()) {
                throw new IllegalArgumentException(
                        "chunkCount must equal chunking.finalChunkCount"
                );
            }
        }
    }

    /**
     * Clean 阶段中可检索与仅治理元素的去向统计。
     *
     * @param indexableElements 实际交给 Chunker 的元素数
     * @param metadataOnlyElements 只保留结构/治理信息的元素数
     * @param reasonCodeCounts 稳定清洗原因码到命中次数的映射
     */
    record CleaningDiagnostics(
            int indexableElements,
            int metadataOnlyElements,
            Map<String, Integer> reasonCodeCounts
    ) {

        /** 把原因码按键排序，保证 API 与 JSON 诊断具有稳定展示顺序。 */
        public CleaningDiagnostics {
            if (indexableElements < 0 || metadataOnlyElements < 0) {
                throw new IllegalArgumentException("cleaning counts must be non-negative");
            }
            Objects.requireNonNull(reasonCodeCounts, "reasonCodeCounts must not be null");
            TreeMap<String, Integer> normalized = new TreeMap<>();
            reasonCodeCounts.forEach((code, count) -> {
                if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
                    throw new IllegalArgumentException("cleaning reason code is invalid");
                }
                if (count == null || count < 0) {
                    throw new IllegalArgumentException(
                            "cleaning reason count must be non-negative"
                    );
                }
                normalized.put(code, count);
            });
            reasonCodeCounts = Collections.unmodifiableMap(normalized);
        }
    }

    /**
     * Chunk 阶段的 16 项确定性与语义边界决策指标。
     *
     * <p>固定字段用于长期评测和页面对比；不能用任意 Map 代替，否则字段拼写变化会
     * 被数据库静默接受，破坏跨版本可比性。计数单位由本次 TokenCounter 合同定义。</p>
     */
    record ChunkDiagnostics(
            int structuralHardBreaks,
            int baselineSoftBreaks,
            int semanticCandidateBoundaries,
            int semanticCutSuggestions,
            int semanticJoinSuggestions,
            int semanticNeutralSuggestions,
            int semanticCutsAdded,
            int semanticJoinsApplied,
            int semanticNoOps,
            int tokenLimitBreaks,
            int spanLimitBreaks,
            int rejectedSemanticJoins,
            int finalChunkCount,
            int minimumChunkUnits,
            double averageChunkUnits,
            int maximumChunkUnits
    ) {

        /** 在 SPI 边界拒绝损坏或无法比较的持久化诊断。 */
        public ChunkDiagnostics {
            if (structuralHardBreaks < 0 || baselineSoftBreaks < 0
                    || semanticCandidateBoundaries < 0 || semanticCutSuggestions < 0
                    || semanticJoinSuggestions < 0 || semanticNeutralSuggestions < 0
                    || semanticCutsAdded < 0 || semanticJoinsApplied < 0
                    || semanticNoOps < 0 || tokenLimitBreaks < 0
                    || spanLimitBreaks < 0 || rejectedSemanticJoins < 0
                    || finalChunkCount < 0 || minimumChunkUnits < 0
                    || maximumChunkUnits < 0 || !Double.isFinite(averageChunkUnits)
                    || averageChunkUnits < 0.0D) {
                throw new IllegalArgumentException(
                        "chunking diagnostics must be finite and non-negative"
                );
            }
            if (semanticCandidateBoundaries != semanticCutSuggestions
                    + semanticJoinSuggestions + semanticNeutralSuggestions) {
                throw new IllegalArgumentException(
                        "semantic suggestion counts are inconsistent"
                );
            }
            if (semanticCutSuggestions + semanticJoinSuggestions
                    != semanticCutsAdded + semanticJoinsApplied
                    + semanticNoOps + rejectedSemanticJoins) {
                throw new IllegalArgumentException(
                        "semantic application counts are inconsistent"
                );
            }
            if (finalChunkCount == 0
                    && (minimumChunkUnits != 0 || averageChunkUnits != 0.0D
                    || maximumChunkUnits != 0)) {
                throw new IllegalArgumentException(
                        "empty chunking result must have zero size metrics"
                );
            }
            if (finalChunkCount > 0
                    && (minimumChunkUnits < 1 || maximumChunkUnits < minimumChunkUnits
                    || averageChunkUnits < minimumChunkUnits
                    || averageChunkUnits > maximumChunkUnits)) {
                throw new IllegalArgumentException("chunk size metrics are inconsistent");
            }
        }
    }

    /** 一个任务内的文件处理结果。 */
    record RunItem(
            UUID id,
            SourceAsset sourceAsset,
            PublicationAttributes publication,
            ItemStatus status,
            ItemStage stage,
            String errorCode,
            ItemDiagnostics diagnostics,
            ExtractionPreview preview,
            DocumentId documentId,
            UUID revisionId,
            Instant startedAt,
            Instant finishedAt
    ) {

        /** 保存文件状态和可选诊断。 */
        public RunItem {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceAsset, "sourceAsset must not be null");
            Objects.requireNonNull(publication, "publication must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(stage, "stage must not be null");
        }
    }

    /** 页面和 Worker 共用的任务快照；items 按创建顺序排列。 */
    record RunSnapshot(
            UUID id,
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ExtractionMode mode,
            RunStatus status,
            String language,
            long configVersion,
            ExtractionConfigSnapshot configSnapshot,
            String datasetId,
            UUID baselineRunId,
            ExtractionGateStatus gateStatus,
            ExtractionGateReport gateReport,
            PrincipalId createdBy,
            String errorCode,
            Instant createdAt,
            Instant readyAt,
            Instant startedAt,
            Instant finishedAt,
            Instant heartbeatAt,
            List<RunItem> items
    ) {

        /** 防御性保存聚合快照。 */
        public RunSnapshot {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(language, "language must not be null");
            if (configVersion != 1L) {
                throw new IllegalArgumentException("configVersion must be 1");
            }
            Objects.requireNonNull(configSnapshot, "configSnapshot must not be null");
            if (datasetId != null) {
                datasetId = requiredIdentifier(datasetId, "datasetId", 128);
            }
            Objects.requireNonNull(gateStatus, "gateStatus must not be null");
            if ((gateStatus == ExtractionGateStatus.NOT_EVALUATED) != (gateReport == null)) {
                throw new IllegalArgumentException("gate status and report are inconsistent");
            }
            if (gateReport != null) {
                if (!Objects.equals(datasetId, gateReport.datasetId())) {
                    throw new IllegalArgumentException("gate report differs from selected dataset");
                }
                if (!configSnapshot.fingerprint().equals(gateReport.configFingerprint())) {
                    throw new IllegalArgumentException("gate report differs from config snapshot");
                }
            }
            Objects.requireNonNull(createdBy, "createdBy must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        }

        public int totalItems() {
            return items.size();
        }

        public long succeededItems() {
            return items.stream().filter(item -> item.status() == ItemStatus.SUCCEEDED).count();
        }

        public long failedItems() {
            return items.stream().filter(item -> item.status() == ItemStatus.FAILED).count();
        }

        /** 返回因同内容幂等命中而未重复发布的文件数。 */
        public long skippedDuplicateItems() {
            return items.stream()
                    .filter(item -> item.status() == ItemStatus.SKIPPED_DUPLICATE)
                    .count();
        }
    }

    /**
     * 创建任务所需的租户边界和一次配置快照。
     *
     * <p>{@code configVersion} 固定为创建时 Space 配置版本 1；TEST_ONLY 的临时
     * 覆盖语义完整保存在 {@code configSnapshot} 及其指纹中，不创建另一条版本线。</p>
     */
    record BeginRequest(
            UUID runId,
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ExtractionMode mode,
            String language,
            long configVersion,
            ExtractionConfigSnapshot configSnapshot,
            String datasetId,
            UUID baselineRunId,
            PrincipalId createdBy,
            Instant createdAt
    ) {

        /** 拒绝不完整或尚未物化的配置。 */
        public BeginRequest {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(language, "language must not be null");
            if (configVersion != 1L) {
                throw new IllegalArgumentException("configVersion must be 1");
            }
            Objects.requireNonNull(configSnapshot, "configSnapshot must not be null");
            if (datasetId != null) {
                datasetId = requiredIdentifier(datasetId, "datasetId", 128);
            }
            Objects.requireNonNull(createdBy, "createdBy must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    private static String requiredIdentifier(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static String requiredText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }
}
