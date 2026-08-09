package dev.infinityknowledge.controlplane.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个 HTTP 请求建立可跨前端、API 日志和 Trace 使用的关联标识。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "dev.infinityknowledge.requestId";
    private static final String MDC_KEY = "request_id";

    /**
     * 复用合法 UUID，否则生成 UUID，并在响应与请求属性中使用同一标识。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        UUID requestId = normalize(request.getHeader(HEADER));
        String value = requestId.toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, value);
        MDC.put(MDC_KEY, value);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 返回当前请求关联标识，非 HTTP 线程使用空字符串。
     *
     * @return 请求标识
     */
    public static String currentRequestId() {
        String requestId = MDC.get(MDC_KEY);
        return requestId == null ? "" : requestId;
    }

    private static UUID normalize(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate);
            } catch (IllegalArgumentException ignored) {
                // 不把不可信的关联标识传播到日志、Trace 或响应。
            }
        }
        return UUID.randomUUID();
    }
}
