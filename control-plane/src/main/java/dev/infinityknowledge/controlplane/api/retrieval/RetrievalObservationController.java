package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.controlplane.application.retrieval.RetrievalObservationReportService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 提供按 request 下钻最新检索 execution 的安全观测入口。 */
@RestController
@RequestMapping("/api/v1/retrieval-observations/requests")
public final class RetrievalObservationController {

    private final JwtPrincipalContextFactory principalFactory;
    private final RetrievalObservationReportService service;

    /** 创建只负责 JWT 映射和 HTTP 视图转换的控制器。 */
    public RetrievalObservationController(
            JwtPrincipalContextFactory principalFactory,
            RetrievalObservationReportService service
    ) {
        this.principalFactory = principalFactory;
        this.service = service;
    }

    /** 返回 JWT 租户内且当前主体仍有权访问的最新 execution 报告。 */
    @GetMapping("/{requestId}")
    public RetrievalObservationReportView latest(
            @PathVariable UUID requestId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return RetrievalObservationReportView.from(
                service.latest(principalFactory.create(jwt), requestId)
        );
    }
}
