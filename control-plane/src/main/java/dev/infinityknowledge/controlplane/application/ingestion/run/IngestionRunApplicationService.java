package dev.infinityknowledge.controlplane.application.ingestion.run;

import dev.infinityknowledge.controlplane.api.document.ingestion.IngestionManifest;
import dev.infinityknowledge.controlplane.application.ingestion.UploadedSourceReader;
import dev.infinityknowledge.controlplane.config.ingestion.ExtractionRunProperties;
import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import dev.infinityknowledge.runtime.extraction.ExtractionRunService;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.PublicationAttributes;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.Objects;

/** 处理正式多文件摄取的管理员鉴权、Manifest 与 Multipart 有界读取。 */
@Service
public final class IngestionRunApplicationService {

    private final ExtractionRunService runtime;
    private final UploadedSourceReader sourceReader;
    private final ExtractionRunProperties properties;

    /** 创建只保留 HTTP 边界职责的正式摄取服务。 */
    public IngestionRunApplicationService(
            ExtractionRunService runtime,
            UploadedSourceReader sourceReader,
            ExtractionRunProperties properties
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.sourceReader = Objects.requireNonNull(
                sourceReader,
                "sourceReader must not be null"
        );
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 保留所有原件并创建一个 {@link ExtractionMode#INGEST} 任务。
     *
     * <p>本方法不解析文档，也不会因后续失败删除 OSS。一个 Space 只能有一个活动
     * Run，由 PostgreSQL 唯一约束保证；每个文件独立记录成功、重复或失败结果。</p>
     */
    public RunSnapshot create(
            PrincipalContext principal,
            String requestedSpaceId,
            MultipartFile[] files,
            IngestionManifest manifest,
            String requestedLanguage
    ) {
        requireAdmin(principal);
        validate(files, manifest);
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(requestedSpaceId);
        return runtime.create(
                principal.tenantId(),
                principal.principalId(),
                spaceId,
                ExtractionMode.INGEST,
                requestedLanguage,
                null,
                null,
                null,
                files.length,
                properties.maximumTotalBytes(),
                (index, remainingBytes) -> {
                    var buffered = sourceReader.read(files[index]);
                    if (remainingBytes < 0L || buffered.bytes().length > remainingBytes) {
                        throw new SourceSizeLimitExceededException();
                    }
                    IngestionManifest.Item item = manifest.items().get(index);
                    String title = item.title() == null || item.title().isBlank()
                            ? buffered.fileName()
                            : DomainChecks.requiredText(item.title(), "title", 512);
                    int authority = item.authority() == null ? 80 : item.authority();
                    return new ExtractionRunService.SourceUpload(
                            buffered.fileName(),
                            buffered.mediaType(),
                            buffered.bytes(),
                            buffered.checksumSha256(),
                            new PublicationAttributes(
                                    DomainChecks.requiredText(
                                            item.externalId(),
                                            "externalId",
                                            512
                                    ),
                                    title,
                                    authority
                            )
                    );
                }
        );
    }

    private void validate(MultipartFile[] files, IngestionManifest manifest) {
        Objects.requireNonNull(files, "files must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        if (files.length < 1 || files.length > properties.maximumFiles()) {
            throw new IllegalArgumentException("files count is outside the configured limit");
        }
        if (manifest.items().size() != files.length) {
            throw new IllegalArgumentException("manifest item count must equal files count");
        }
        long declaredTotal = 0L;
        var externalIds = new HashSet<String>();
        for (int index = 0; index < files.length; index++) {
            MultipartFile file = Objects.requireNonNull(
                    files[index],
                    "files must not contain null"
            );
            String externalId = DomainChecks.requiredText(
                    manifest.items().get(index).externalId(),
                    "externalId",
                    512
            );
            if (!externalIds.add(externalId)) {
                throw new IllegalArgumentException(
                        "manifest must not contain duplicate externalId"
                );
            }
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

    /** 限制正式发布仅供本租户知识管理员或受信系统主体使用。 */
    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal()
                && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
