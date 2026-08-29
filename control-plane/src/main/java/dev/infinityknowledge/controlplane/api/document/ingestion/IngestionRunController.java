package dev.infinityknowledge.controlplane.api.document.ingestion;

import dev.infinityknowledge.controlplane.api.document.extraction.ExtractionRunViews;
import dev.infinityknowledge.controlplane.application.ingestion.run.IngestionRunApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Space 维度的正式多文件异步摄取入口。 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/ingestion-runs")
public final class IngestionRunController {

    private final JwtPrincipalContextFactory principalFactory;
    private final IngestionRunApplicationService service;

    /** 创建只负责认证、Multipart 绑定和响应映射的 Controller。 */
    public IngestionRunController(
            JwtPrincipalContextFactory principalFactory,
            IngestionRunApplicationService service
    ) {
        this.principalFactory = principalFactory;
        this.service = service;
    }

    /**
     * 接收多个原件与逐文件 Manifest，返回 202 排队任务。
     *
     * <p>任务状态、详情、取消和原件下载沿用同一 Space 的
     * {@code /extraction-runs} 查询 API；响应中的 {@code mode=INGEST} 明确区分正式
     * 发布与测试广场。请求不接受 Parser 或 Chunker 临时覆盖。</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExtractionRunViews.Detail> create(
            @PathVariable String spaceId,
            @RequestPart("files") MultipartFile[] files,
            @RequestPart("manifest") IngestionManifest manifest,
            @RequestPart(value = "language", required = false) String language,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var run = service.create(
                principalFactory.create(jwt),
                spaceId,
                files,
                manifest,
                language == null ? "zh-CN" : language
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ExtractionRunViews.Detail.from(run));
    }
}
