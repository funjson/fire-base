package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.controlplane.application.retrieval.OnlineRetrievalObservabilityService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供服务端固定 ONLINE 用途的检索统计、分层诊断和执行记录。 */
@RestController
@RequestMapping("/api/v1/retrieval-observability/online")
public final class RetrievalObservabilityController {
    private final JwtPrincipalContextFactory principalFactory;
    private final OnlineRetrievalObservabilityService service;

    /** 创建只负责 JWT 映射、类型化参数和 HTTP 视图转换的控制器。 */
    public RetrievalObservabilityController(
            JwtPrincipalContextFactory principalFactory,
            OnlineRetrievalObservabilityService service
    ) {
        this.principalFactory = principalFactory;
        this.service = service;
    }

    /** 返回在线窗口总览和固定 UTC 时间桶趋势。 */
    @GetMapping("/overview")
    public OnlineOverviewView overview(
            @ModelAttribute OnlineObservabilityQueryRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return OnlineOverviewView.from(service.overview(
                principalFactory.create(jwt),
                request.toFilters()
        ));
    }

    /** 返回同一执行范围下的逐层诊断。 */
    @GetMapping("/stages")
    public StageDiagnosticsView stages(
            @ModelAttribute OnlineObservabilityQueryRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return StageDiagnosticsView.from(service.stages(
                principalFactory.create(jwt),
                request.toFilters()
        ));
    }

    /** 返回安全执行记录；单次详情继续复用 requestId 报告入口。 */
    @GetMapping("/executions")
    public RetrievalExecutionPageView executions(
            @ModelAttribute OnlineObservabilityQueryRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) RetrievalTerminalStatus terminalStatus,
            @RequestParam(required = false) RetrievalStopReason stopReason,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return RetrievalExecutionPageView.from(service.executions(
                principalFactory.create(jwt),
                request.toFilters(),
                page,
                size,
                terminalStatus,
                stopReason
        ));
    }
}
