package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.SpaceDocumentProcessingConfigService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 提供页面可用的空间文档处理配置接口。 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public final class SpaceDocumentProcessingConfigController {

    private final JwtPrincipalContextFactory principalFactory;
    private final SpaceDocumentProcessingConfigService service;

    /** 创建空间文档处理配置 API。 */
    public SpaceDocumentProcessingConfigController(
            JwtPrincipalContextFactory principalFactory,
            SpaceDocumentProcessingConfigService service
    ) {
        this.principalFactory = Objects.requireNonNull(
                principalFactory,
                "principalFactory must not be null"
        );
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    /** 返回创建时固化的只读配置以及部署实际安装的选项。 */
    @GetMapping("/document-processing-config")
    public SpaceDocumentProcessingConfigView get(
            @PathVariable String spaceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return SpaceDocumentProcessingConfigView.from(
                service.get(principalFactory.create(jwt), spaceId)
        );
    }

}
