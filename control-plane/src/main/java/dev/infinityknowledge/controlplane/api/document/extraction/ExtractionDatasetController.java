package dev.infinityknowledge.controlplane.api.document.extraction;

import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequest;
import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequestMapper;
import dev.infinityknowledge.controlplane.application.ingestion.extraction.ExtractionDatasetCatalog;
import dev.infinityknowledge.controlplane.application.ingestion.extraction.ExtractionRunApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Space 测试广场的 Golden Dataset 目录和真实验收运行入口。 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/extraction-datasets")
public final class ExtractionDatasetController {

    private final JwtPrincipalContextFactory principalFactory;
    private final ExtractionRunApplicationService service;

    /** Controller 只负责认证、HTTP 校验和响应映射。 */
    public ExtractionDatasetController(
            JwtPrincipalContextFactory principalFactory,
            ExtractionRunApplicationService service
    ) {
        this.principalFactory = principalFactory;
        this.service = service;
    }

    /** 列出可执行 Dataset 及不含正文的 Case 摘要。 */
    @GetMapping
    public List<ExtractionDatasetCatalog.DatasetDescriptor> list(
            @PathVariable String spaceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        // spaceId 保留在路由中，使页面权限和后续 Space 私有 Dataset 扩展保持一致。
        new dev.infinityknowledge.domain.space.KnowledgeSpaceId(spaceId);
        return service.datasets(principalFactory.create(jwt));
    }

    /** 使用服务端 Golden Sources 创建与普通多文件上传相同的 TEST_ONLY Run。 */
    @PostMapping("/{datasetId}/runs")
    public ResponseEntity<ExtractionRunViews.Detail> run(
            @PathVariable String spaceId,
            @PathVariable String datasetId,
            @Valid @RequestBody DatasetRunRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var principal = principalFactory.create(jwt);
        var run = service.createFromDataset(
                principal,
                spaceId,
                datasetId,
                request.language(),
                request.baselineRunId(),
                request.testConfig() == null ? null
                        : DocumentProcessingConfigRequestMapper.map(
                                principal,
                                spaceId,
                                request.testConfig()
                        )
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ExtractionRunViews.Detail.from(run));
    }

    /** Dataset Run 可选择同 Dataset Baseline 和本次测试覆盖配置。 */
    public record DatasetRunRequest(
            @NotBlank @Size(max = 32) String language,
            UUID baselineRunId,
            @Valid DocumentProcessingConfigRequest testConfig
    ) {
    }
}
