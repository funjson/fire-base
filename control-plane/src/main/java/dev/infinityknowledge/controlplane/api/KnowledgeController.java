package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.KnowledgeFacade;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * 暴露面向 Agent 的版本化知识检索 API。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public final class KnowledgeController {
    private final KnowledgeFacade facade;

    /**
     * 创建控制器。
     *
     * @param facade 知识应用服务
     */
    public KnowledgeController(KnowledgeFacade facade) {
        this.facade = Objects.requireNonNull(facade, "facade must not be null");
    }

    /**
     * 在当前 JWT 主体权限内生成证据包。
     *
     * @param request 查询请求
     * @param jwt 已验证 JWT
     * @param requestId 入口过滤器规范化的请求标识
     * @return 证据包
     */
    @PostMapping("/query")
    public KnowledgeQueryResponse query(
            @Valid @RequestBody KnowledgeQueryRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID requestId
    ) {
        return KnowledgeQueryResponse.from(facade.query(request, jwt, requestId));
    }
}
