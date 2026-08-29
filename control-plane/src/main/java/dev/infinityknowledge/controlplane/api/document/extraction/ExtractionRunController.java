package dev.infinityknowledge.controlplane.api.document.extraction;

import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequest;
import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequestMapper;
import dev.infinityknowledge.controlplane.application.ingestion.extraction.ExtractionRunApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 空间维度的多文件抽取试验与受控原件下载 API。 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/extraction-runs")
public final class ExtractionRunController {

    private final JwtPrincipalContextFactory principalFactory;
    private final ExtractionRunApplicationService service;

    /** 创建只保留 HTTP、认证与响应映射职责的 Controller。 */
    public ExtractionRunController(
            JwtPrincipalContextFactory principalFactory,
            ExtractionRunApplicationService service
    ) {
        this.principalFactory = principalFactory;
        this.service = service;
    }

    /**
     * 接收多个文件并创建 {@code TEST_ONLY} 抽取任务。
     *
     * <p>Multipart 字段名固定为 {@code files}，可以重复出现；任务不会写入正式
     * Document、Revision 或索引投影。</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExtractionRunViews.Detail> create(
            @PathVariable String spaceId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "language", defaultValue = "zh-CN") String language,
            @RequestParam(value = "datasetId", required = false) String datasetId,
            @RequestParam(value = "baselineRunId", required = false) UUID baselineRunId,
            @Valid @RequestPart(value = "testConfig", required = false)
            DocumentProcessingConfigRequest testConfig,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var principal = principalFactory.create(jwt);
        var run = service.create(
                principal,
                spaceId,
                files,
                language,
                datasetId,
                baselineRunId,
                testConfig == null ? null
                        : DocumentProcessingConfigRequestMapper.map(
                                principal,
                                spaceId,
                                testConfig
                        )
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ExtractionRunViews.Detail.from(run));
    }

    /** 返回当前空间最近的抽取任务摘要。 */
    @GetMapping
    public List<ExtractionRunViews.Summary> list(
            @PathVariable String spaceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.list(principalFactory.create(jwt), spaceId).stream()
                .map(ExtractionRunViews.Summary::from)
                .toList();
    }

    /** 返回任务及每个来源文件的阶段结果。 */
    @GetMapping("/{runId}")
    public ExtractionRunViews.Detail detail(
            @PathVariable String spaceId,
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ExtractionRunViews.Detail.from(
                service.detail(principalFactory.create(jwt), spaceId, runId)
        );
    }

    /** 请求协作式取消任务；终态任务重复调用仍返回当前终态。 */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<ExtractionRunViews.Detail> cancel(
            @PathVariable String spaceId,
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var run = service.cancel(principalFactory.create(jwt), spaceId, runId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ExtractionRunViews.Detail.from(run));
    }

    /**
     * 下载一个任务 Item 对应的 OSS 原件。
     *
     * <p>响应始终使用 attachment、no-store 与 nosniff，不返回 MinIO Bucket、
     * 物理 Key 或 storageId。</p>
     */
    @GetMapping("/{runId}/items/{itemId}/source")
    public ResponseEntity<InputStreamResource> source(
            @PathVariable String spaceId,
            @PathVariable UUID runId,
            @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var download = service.openSource(
                principalFactory.create(jwt),
                spaceId,
                runId,
                itemId
        );
        try {
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(download.fileName(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.parseMediaType(download.mediaType()))
                    .contentLength(download.contentLength())
                    .body(new InputStreamResource(download.storedObject().content()));
        } catch (RuntimeException failure) {
            try {
                download.storedObject().close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
