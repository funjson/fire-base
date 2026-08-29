package dev.infinityknowledge.controlplane.application.ingestion.extraction;

import dev.infinityknowledge.controlplane.config.ingestion.ExtractionRunProperties;
import dev.infinityknowledge.controlplane.application.ingestion.UploadedSourceReader;
import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import dev.infinityknowledge.runtime.extraction.ExtractionRunService;
import dev.infinityknowledge.runtime.extraction.ExtractionConfigSnapshots;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
        .SpaceDocumentProcessingConfig;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 处理多文件抽取试验的鉴权与 Multipart 有界读取边界。
 *
 * <p>OSS、任务状态和配置快照由纯 Java {@link ExtractionRunService} 负责；本类只按
 * 索引延迟读取一个 MultipartFile，并把同一份有界字节交给 Runtime，不聚合来源。</p>
 */
@Service
public final class ExtractionRunApplicationService {

    private final ExtractionRunService runtime;
    private final UploadedSourceReader sourceReader;
    private final ExtractionRunProperties properties;
    private final ExtractionDatasetCatalog datasets;
    private final DocumentProcessingCapabilities capabilities;

    /** 创建仅用于试验且不具备发布端口的应用服务。 */
    public ExtractionRunApplicationService(
            ExtractionRunService runtime,
            UploadedSourceReader sourceReader,
            ExtractionRunProperties properties,
            ExtractionDatasetCatalog datasets,
            DocumentProcessingCapabilities capabilities
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.sourceReader = Objects.requireNonNull(
                sourceReader,
                "sourceReader must not be null"
        );
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.datasets = Objects.requireNonNull(datasets, "datasets must not be null");
        this.capabilities = Objects.requireNonNull(
                capabilities,
                "capabilities must not be null"
        );
    }

    /** 接收一次多文件上传并返回已经 seal 的排队任务。 */
    public RunSnapshot create(
            PrincipalContext principal,
            String requestedSpaceId,
            MultipartFile[] files,
            String requestedLanguage,
            String datasetId,
            UUID baselineRunId,
            SpaceDocumentProcessingConfig testConfig
    ) {
        requireAdmin(principal);
        validateFiles(files);
        if (datasetId != null) {
            datasets.require(datasetId);
        }
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        ExtractionConfigSnapshot testConfigSnapshot = testConfigSnapshot(
                principal,
                spaceId,
                testConfig
        );
        return runtime.create(
                principal.tenantId(),
                principal.principalId(),
                spaceId,
                ExtractionMode.TEST_ONLY,
                requestedLanguage,
                datasetId,
                baselineRunId,
                testConfigSnapshot,
                files.length,
                properties.maximumTotalBytes(),
                (index, remainingBytes) -> {
                    var buffered = sourceReader.read(files[index]);
                    if (remainingBytes < 0L
                            || buffered.bytes().length > remainingBytes) {
                        throw new SourceSizeLimitExceededException();
                    }
                    return new ExtractionRunService.SourceUpload(
                            buffered.fileName(),
                            buffered.mediaType(),
                            buffered.bytes(),
                            buffered.checksumSha256()
                    );
                }
        );
    }

    /** 返回页面可选择的数据集，不包含 Source 或 Artifact 正文。 */
    public List<ExtractionDatasetCatalog.DatasetDescriptor> datasets(
            PrincipalContext principal
    ) {
        requireAdmin(principal);
        return datasets.descriptors();
    }

    /**
     * 使用服务端受版本控制的 Golden Sources 创建真实验收 Run。
     *
     * <p>用户无需重新上传 Fixture；每个 Case 的原件字节和 SHA 已由 Catalog 加载时
     * 校验，仍通过同一 OSS、任务和 ExtractionEngine 链路执行。</p>
     */
    public RunSnapshot createFromDataset(
            PrincipalContext principal,
            String requestedSpaceId,
            String datasetId,
            String requestedLanguage,
            UUID baselineRunId,
            SpaceDocumentProcessingConfig testConfig
    ) {
        requireAdmin(principal);
        var dataset = datasets.require(datasetId);
        if (dataset.cases().size() > properties.maximumFiles()) {
            throw new IllegalArgumentException("dataset contains too many source files");
        }
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        ExtractionConfigSnapshot testConfigSnapshot = testConfigSnapshot(
                principal,
                spaceId,
                testConfig
        );
        return runtime.create(
                principal.tenantId(),
                principal.principalId(),
                spaceId,
                ExtractionMode.TEST_ONLY,
                requestedLanguage,
                dataset.datasetVersion(),
                baselineRunId,
                testConfigSnapshot,
                dataset.cases().size(),
                properties.maximumTotalBytes(),
                (index, remainingBytes) -> {
                    var value = dataset.cases().get(index);
                    byte[] source = value.sourceBytes();
                    if (source.length > remainingBytes) {
                        throw new SourceSizeLimitExceededException();
                    }
                    String fileName = java.nio.file.Path.of(value.sourceName())
                            .getFileName()
                            .toString();
                    return new ExtractionRunService.SourceUpload(
                            fileName,
                            value.mediaType(),
                            source,
                            value.sourceSha256()
                    );
                }
        );
    }

    /**
     * 校验本次测试覆盖与当前租户、Space、部署能力一致，并固化服务端权威指纹。
     *
     * <p>该路径不会写入 Space 配置，因此测试参数只影响本次 TEST_ONLY Run，
     * 不会修改正式 INGEST 使用的不可变配置。</p>
     */
    private ExtractionConfigSnapshot testConfigSnapshot(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SpaceDocumentProcessingConfig testConfig
    ) {
        if (testConfig == null) {
            return null;
        }
        if (!testConfig.tenantId().equals(principal.tenantId())
                || !testConfig.spaceId().equals(spaceId)
                || testConfig.version() != 0L) {
            throw new IllegalArgumentException(
                    "test config does not belong to this request"
            );
        }
        return ExtractionConfigSnapshots.capture(
                testConfig,
                capabilities.processingContract(testConfig),
                DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
        );
    }

    /** 查询空间最近的有界任务列表。 */
    public List<RunSnapshot> list(PrincipalContext principal, String requestedSpaceId) {
        requireAdmin(principal);
        return runtime.list(
                principal.tenantId(),
                new KnowledgeSpaceId(requestedSpaceId),
                properties.listLimit()
        );
    }

    /** 查询空间内一个任务及逐文件诊断。 */
    public RunSnapshot detail(
            PrincipalContext principal,
            String requestedSpaceId,
            UUID runId
    ) {
        requireAdmin(principal);
        return find(principal, new KnowledgeSpaceId(requestedSpaceId), runId);
    }

    /** 请求取消；排队任务立即取消，运行任务在文件边界协作式收尾。 */
    public RunSnapshot cancel(
            PrincipalContext principal,
            String requestedSpaceId,
            UUID runId
    ) {
        requireAdmin(principal);
        return runtime.cancel(
                principal.tenantId(),
                new KnowledgeSpaceId(requestedSpaceId),
                runId
        ).orElseThrow(ExtractionRunNotFoundException::new);
    }

    /**
     * 打开经租户、空间和管理员校验的试验原件。
     *
     * <p>返回值只携带原始文件名、媒体类型和流；Controller 不会暴露 storageId、
     * Bucket 或物理对象 Key。</p>
     */
    public ExtractionRunService.SourceDownload openSource(
            PrincipalContext principal,
            String requestedSpaceId,
            UUID runId,
            UUID itemId
    ) {
        requireAdmin(principal);
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        return runtime.openSource(
                principal.tenantId(),
                spaceId,
                runId,
                itemId
        ).orElseThrow(ExtractionRunNotFoundException::new);
    }

    private RunSnapshot find(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            UUID runId
    ) {
        return runtime.find(principal.tenantId(), spaceId, runId)
                .orElseThrow(ExtractionRunNotFoundException::new);
    }

    private void validateFiles(MultipartFile[] files) {
        Objects.requireNonNull(files, "files must not be null");
        if (files.length < 1 || files.length > properties.maximumFiles()) {
            throw new IllegalArgumentException("files count is outside the configured limit");
        }
        long declaredTotal = 0L;
        for (MultipartFile file : files) {
            Objects.requireNonNull(file, "files must not contain null");
            long declared = file.getSize();
            if (declared > 0L) {
                try {
                    declaredTotal = Math.addExact(declaredTotal, declared);
                } catch (ArithmeticException overflow) {
                    throw new SourceSizeLimitExceededException(overflow);
                }
                if (declaredTotal > properties.maximumTotalBytes()) {
                    throw new SourceSizeLimitExceededException();
                }
            }
        }
    }

    /** 限制试验任务与原件下载仅供本租户知识管理员或系统主体使用。 */
    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
