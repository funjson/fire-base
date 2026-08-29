package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.BeginRequest;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.PublicationAttributes;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.SourceAsset;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
        .SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 编排多文件抽取任务的配置快照、OSS 原件与任务状态。
 *
 * <p>HTTP 层通过 {@link SourceReader} 按索引延迟提供一个有界来源，本服务每次只
 * 持有一个来源字节数组，绝不聚合 {@code List<byte[]>}。本类型没有 KnowledgeWriter
 * 或 Projection 依赖；调用方通过显式 mode 决定由哪个 Processor 继续处理。</p>
 *
 * <p>OSS put 与 PostgreSQL appendSource 无法组成同一事务。如果 put 成功而目录
 * 追加失败，对象会按“不删除原件”约束保留，并携带可对账的 {@code run-id} 元数据；
 * Run 写入稳定错误 {@code SOURCE_UPLOAD_FAILED}。后续 orphan reconciliation 应按
 * 该元数据补目录或告警，本轮不会用补偿删除掩盖跨系统窗口。</p>
 */
public final class ExtractionRunService {

    private final ExtractionRunStore store;
    private final ObjectStorage objectStorage;
    private final SpaceDocumentProcessingConfigResolver configResolver;
    private final Clock clock;

    /** 创建不依赖 Spring、HTTP 或正式知识发布端口的 Runtime 服务。 */
    public ExtractionRunService(
            ExtractionRunStore store,
            ObjectStorage objectStorage,
            SpaceDocumentProcessingConfigResolver configResolver,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.objectStorage = Objects.requireNonNull(
                objectStorage,
                "objectStorage must not be null"
        );
        this.configResolver = Objects.requireNonNull(
                configResolver,
                "configResolver must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 逐文件读取并保留来源，随后把任务 seal 为可领取状态。
     *
     * @param fileCount 本批 Multipart 文件数
     * @param maximumTotalBytes 本批实际来源正文的总字节上限
     * @param sourceReader HTTP 边界提供的逐文件有界读取回调
     */
    public RunSnapshot create(
            TenantId tenantId,
            PrincipalId createdBy,
            KnowledgeSpaceId spaceId,
            String requestedLanguage,
            int fileCount,
            long maximumTotalBytes,
            SourceReader sourceReader
    ) {
        return create(
                tenantId,
                createdBy,
                spaceId,
                ExtractionMode.TEST_ONLY,
                requestedLanguage,
                null,
                null,
                null,
                fileCount,
                maximumTotalBytes,
                sourceReader
        );
    }

    /**
     * 使用指定模式、Dataset 和可选 Baseline 创建任务。
     *
     * <p>测试广场传 {@link ExtractionMode#TEST_ONLY}，正式多文件入口传
     * {@link ExtractionMode#INGEST}；二者复用同一套上传、OSS、配置快照和任务状态
     * 链路，再由不同 Processor 隔离“只测试”与“允许发布”的权限边界。</p>
     */
    public RunSnapshot create(
            TenantId tenantId,
            PrincipalId createdBy,
            KnowledgeSpaceId spaceId,
            ExtractionMode mode,
            String requestedLanguage,
            String datasetId,
            UUID baselineRunId,
            ExtractionConfigSnapshot testConfigSnapshot,
            int fileCount,
            long maximumTotalBytes,
            SourceReader sourceReader
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(sourceReader, "sourceReader must not be null");
        if (fileCount < 1 || fileCount > 100) {
            throw new IllegalArgumentException("fileCount must be between 1 and 100");
        }
        if (maximumTotalBytes < 1L) {
            throw new IllegalArgumentException("maximumTotalBytes must be positive");
        }
        String language = IngestionIdentity.normalizeLanguageTag(requestedLanguage);
        var config = testConfigSnapshot == null
                ? configResolver.resolve(tenantId, spaceId)
                : configResolver.resolveStored(tenantId, spaceId);
        if (config.version() != 1L) {
            throw new IllegalStateException(
                    "extraction requires the immutable config created with the space"
            );
        }
        validateBaseline(
                tenantId,
                spaceId,
                mode,
                datasetId,
                language,
                baselineRunId
        );
        var snapshot = effectiveSnapshot(
                tenantId,
                spaceId,
                mode,
                config.version(),
                config,
                testConfigSnapshot
        );
        UUID runId = UUID.randomUUID();
        store.begin(new BeginRequest(
                runId,
                tenantId,
                spaceId,
                mode,
                language,
                config.version(),
                snapshot,
                datasetId,
                baselineRunId,
                createdBy,
                clock.instant()
        ));
        try {
            long totalBytes = 0L;
            for (int index = 0; index < fileCount; index++) {
                SourceUpload upload = Objects.requireNonNull(
                        sourceReader.read(index, maximumTotalBytes - totalBytes),
                        "sourceReader returned null"
                );
                totalBytes = addWithinLimit(
                        totalBytes,
                        upload.bytes().length,
                        maximumTotalBytes
                );
                SourceAsset source = storeSource(
                        tenantId,
                        spaceId,
                        runId,
                        mode,
                        upload
                );
                store.appendSource(
                        runId,
                        source,
                        upload.publication(),
                        clock.instant()
                );
            }
            return store.seal(runId, fileCount, clock.instant());
        } catch (SourceSizeLimitExceededException failure) {
            store.failCreation(runId, "SOURCE_TOO_LARGE", clock.instant());
            throw failure;
        } catch (RuntimeException failure) {
            store.failCreation(runId, "SOURCE_UPLOAD_FAILED", clock.instant());
            throw failure;
        }
    }

    /**
     * Baseline 必须是同一 Space、同一 Dataset 已完成门禁计算的 TEST_ONLY Run。
     * Gate 失败仍可作为劣质基线参与对比；未评测或评测异常不能冒充可比较结果。
     */
    private void validateBaseline(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ExtractionMode mode,
            String datasetId,
            String language,
            UUID baselineRunId
    ) {
        if (baselineRunId == null) {
            return;
        }
        RunSnapshot baseline = store.find(tenantId, spaceId, baselineRunId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "baseline run does not exist in this space"
                ));
        if (mode != ExtractionMode.TEST_ONLY
                || baseline.mode() != ExtractionMode.TEST_ONLY
                || baseline.status() != ExtractionRunStore.RunStatus.SUCCEEDED) {
            throw new IllegalArgumentException("baseline run is not evaluable");
        }
        if (!Objects.equals(datasetId, baseline.datasetId())) {
            throw new IllegalArgumentException("baseline run uses a different dataset");
        }
        if (!language.equals(baseline.language())) {
            throw new IllegalArgumentException("baseline run uses a different language");
        }
        if (baseline.gateStatus() == ExtractionGateStatus.NOT_EVALUATED
                || baseline.gateStatus() == ExtractionGateStatus.ERROR) {
            throw new IllegalArgumentException("baseline run has no comparable gate report");
        }
    }

    /**
     * 选择本次任务的不可变处理快照，并拒绝客户端伪造的合同或指纹。
     *
     * <p>{@code configVersion} 仍记录创建时 Space 基线版本；测试覆盖的实际处理
     * 语义完全由返回的快照与指纹描述，不会覆盖 Space 配置。</p>
     */
    private static ExtractionConfigSnapshot effectiveSnapshot(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ExtractionMode mode,
            long activeConfigVersion,
            SpaceDocumentProcessingConfig activeConfig,
            ExtractionConfigSnapshot testConfigSnapshot
    ) {
        if (testConfigSnapshot == null) {
            return ExtractionConfigSnapshots.capture(
                    activeConfig,
                    activeConfig.processingContract(),
                    DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
            );
        }
        if (mode != ExtractionMode.TEST_ONLY) {
            throw new IllegalArgumentException(
                    "test config is only allowed for TEST_ONLY runs"
            );
        }
        var restored = ExtractionConfigSnapshots.restore(
                tenantId,
                spaceId,
                activeConfigVersion,
                testConfigSnapshot
        );
        var verified = ExtractionConfigSnapshots.capture(
                restored,
                testConfigSnapshot.processingContract(),
                testConfigSnapshot.normalizerContract()
        );
        if (!verified.equals(testConfigSnapshot)) {
            throw new IllegalArgumentException(
                    "test config snapshot contract or fingerprint is invalid"
            );
        }
        return verified;
    }

    /** 查询空间最近的有界任务。 */
    public List<RunSnapshot> list(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            int limit
    ) {
        return store.list(tenantId, spaceId, limit);
    }

    /** 查询空间内的单个任务。 */
    public Optional<RunSnapshot> find(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId
    ) {
        return store.find(tenantId, spaceId, runId);
    }

    /** 请求取消并返回最新状态。 */
    public Optional<RunSnapshot> cancel(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId
    ) {
        return store.requestCancel(tenantId, spaceId, runId, clock.instant());
    }

    /**
     * 解析任务 Item 的逻辑对象地址并打开 OSS 流。
     *
     * <p>调用方只能得到文件响应元数据和流，不会看到 storageId 或物理对象 Key。</p>
     */
    public Optional<SourceDownload> openSource(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId,
            UUID itemId
    ) {
        return find(tenantId, spaceId, runId).flatMap(run -> run.items().stream()
                .filter(item -> item.id().equals(itemId))
                .findFirst()
                .flatMap(item -> {
                    var asset = item.sourceAsset();
                    return objectStorage.get(new ObjectAddress(
                            tenantId,
                            spaceId,
                            asset.objectId()
                    )).flatMap(stored -> verifiedDownload(asset, stored));
                }));
    }

    /**
     * 只在对象存储返回的完整性元数据仍与不可变目录一致时开放下载。
     *
     * <p>不一致对象会先关闭响应流，再按“未找到可安全下载的原件”处理；既不暴露
     * 物理存储信息，也不会把可疑字节发送给页面。</p>
     */
    private static Optional<SourceDownload> verifiedDownload(
            SourceAsset asset,
            StoredObject stored
    ) {
        var metadata = stored.metadata();
        boolean matches = metadata.address().tenantId().equals(asset.tenantId())
                && metadata.address().spaceId().equals(asset.spaceId())
                && metadata.address().objectId().equals(asset.objectId())
                && metadata.storageId().equals(asset.storageId())
                && metadata.originalFileName().equals(asset.fileName())
                && metadata.mediaType().equals(asset.mediaType())
                && metadata.contentLength() == asset.contentLength()
                && metadata.checksumSha256().equals(asset.checksumSha256());
        if (!matches) {
            closeQuietly(stored);
            return Optional.empty();
        }
        return Optional.of(new SourceDownload(
                asset.fileName(),
                asset.mediaType(),
                asset.contentLength(),
                stored
        ));
    }

    private static void closeQuietly(StoredObject stored) {
        try {
            stored.close();
        } catch (IOException ignored) {
            // 完整性拒绝已经是最终结果；关闭失败不得泄露 Provider 响应细节。
        }
    }

    private SourceAsset storeSource(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId,
            ExtractionMode mode,
            SourceUpload source
    ) {
        UUID assetId = UUID.randomUUID();
        ObjectAddress address = new ObjectAddress(
                tenantId,
                spaceId,
                "source-asset-" + assetId
        );
        String sourceKind = mode == ExtractionMode.INGEST
                ? "ingestion" : "extraction-test";
        var stored = objectStorage.put(
                new ObjectWriteRequest(
                        address,
                        source.fileName(),
                        source.mediaType(),
                        source.bytes().length,
                        source.checksumSha256(),
                        Map.of("source", sourceKind, "run-id", runId.toString())
                ),
                new ByteArrayInputStream(source.bytes())
        );
        if (!stored.address().equals(address)
                || !stored.originalFileName().equals(source.fileName())
                || !stored.mediaType().equals(source.mediaType())
                || stored.contentLength() != source.bytes().length
                || !stored.checksumSha256().equals(source.checksumSha256())) {
            throw new IllegalStateException("object storage returned inconsistent source metadata");
        }
        return new SourceAsset(
                assetId,
                tenantId,
                spaceId,
                address.objectId(),
                stored.storageId(),
                stored.originalFileName(),
                stored.mediaType(),
                stored.contentLength(),
                stored.checksumSha256(),
                stored.storedAt()
        );
    }

    private static long addWithinLimit(long total, int next, long maximum) {
        long updated;
        try {
            updated = Math.addExact(total, next);
        } catch (ArithmeticException overflow) {
            throw new SourceSizeLimitExceededException(overflow);
        }
        if (updated > maximum) {
            throw new SourceSizeLimitExceededException();
        }
        return updated;
    }

    /** HTTP 边界按索引提供一个已经完成有界读取与摘要计算的来源。 */
    @FunctionalInterface
    public interface SourceReader {
        /**
         * @param index 从 0 开始的 Multipart 文件索引
         * @param remainingBytes 当前批次仍允许读取的来源正文字节数
         */
        SourceUpload read(int index, long remainingBytes);
    }

    /**
     * 单个文件的同步有界快照。
     *
     * <p>{@code bytes} 只在本轮方法调用内视为只读，不执行第二次大数组复制。</p>
     */
    public record SourceUpload(
            String fileName,
            String mediaType,
            byte[] bytes,
            String checksumSha256,
            PublicationAttributes publication
    ) {

        /** 保存 Parser 与 OSS 共同使用的一份来源字节。 */
        public SourceUpload {
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(bytes, "bytes must not be null");
            Objects.requireNonNull(checksumSha256, "checksumSha256 must not be null");
            Objects.requireNonNull(publication, "publication must not be null");
            if (!IngestionIdentity.sha256(bytes).equals(checksumSha256)) {
                throw new IllegalArgumentException("source checksum does not match bytes");
            }
        }

        /** 测试广场默认以文件名作为外部键和标题，并使用标准权限分值 80。 */
        public SourceUpload(
                String fileName,
                String mediaType,
                byte[] bytes,
                String checksumSha256
        ) {
            this(
                    fileName,
                    mediaType,
                    bytes,
                    checksumSha256,
                    new PublicationAttributes(fileName, fileName, 80)
            );
        }
    }

    /** 经租户和空间解析的原件下载流。 */
    public record SourceDownload(
            String fileName,
            String mediaType,
            long contentLength,
            StoredObject storedObject
    ) {

        /** 保存安全响应头所需的逻辑元数据。 */
        public SourceDownload {
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(storedObject, "storedObject must not be null");
        }
    }
}
