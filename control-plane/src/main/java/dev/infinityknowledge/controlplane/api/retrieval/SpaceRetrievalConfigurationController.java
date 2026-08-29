package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.controlplane.application.retrieval.SpaceRetrievalConfigurationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供 Space 当前检索配置、历史修订和乐观锁更新入口。 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/retrieval-configuration")
public final class SpaceRetrievalConfigurationController {

    private final JwtPrincipalContextFactory principalFactory;
    private final SpaceRetrievalConfigurationService service;

    /** 创建保持 HTTP 和应用编排分离的检索配置控制器。 */
    public SpaceRetrievalConfigurationController(
            JwtPrincipalContextFactory principalFactory,
            SpaceRetrievalConfigurationService service
    ) {
        this.principalFactory = principalFactory;
        this.service = service;
    }

    /** 返回当前主体有权读取的 Space 当前配置。 */
    @GetMapping
    public SpaceRetrievalConfigurationView current(
            @PathVariable String spaceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return SpaceRetrievalConfigurationView.from(
                service.current(principalFactory.create(jwt), spaceId)
        );
    }

    /** 追加并切换一个完整配置修订；过期 expectedRevision 返回 409。 */
    @PutMapping
    public SpaceRetrievalConfigurationView update(
            @PathVariable String spaceId,
            @Valid @RequestBody UpdateSpaceRetrievalConfigurationRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return SpaceRetrievalConfigurationView.from(service.update(
                principalFactory.create(jwt),
                spaceId,
                request.expectedRevision(),
                request.configuration().toDomain()
        ));
    }

    /** 按修订号倒序返回有限历史，供控制台审计和回看。 */
    @GetMapping("/history")
    public List<SpaceRetrievalConfigurationView> history(
            @PathVariable String spaceId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.history(principalFactory.create(jwt), spaceId, limit).stream()
                .map(SpaceRetrievalConfigurationView::from)
                .toList();
    }
}
