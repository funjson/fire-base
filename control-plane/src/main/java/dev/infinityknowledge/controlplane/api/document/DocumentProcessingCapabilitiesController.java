package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.SpaceDocumentProcessingConfigService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 提供不依赖既有 Space 的文档处理能力发现接口。 */
@RestController
@RequestMapping("/api/v1/document-processing-capabilities")
public final class DocumentProcessingCapabilitiesController {

    private final JwtPrincipalContextFactory principalFactory;
    private final SpaceDocumentProcessingConfigService service;

    /** 创建管理员能力目录 API。 */
    public DocumentProcessingCapabilitiesController(
            JwtPrincipalContextFactory principalFactory,
            SpaceDocumentProcessingConfigService service
    ) {
        this.principalFactory = Objects.requireNonNull(
                principalFactory,
                "principalFactory must not be null"
        );
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    /** 返回创建表单的默认值和当前部署实际可选能力。 */
    @GetMapping
    public DocumentProcessingCapabilitiesView get(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return DocumentProcessingCapabilitiesView.from(
                service.capabilities(principalFactory.create(jwt))
        );
    }
}
