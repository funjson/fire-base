package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.controlplane.application.retrieval.OnlineRetrievalObservabilityService;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/** 在线观测的类型化 HTTP 查询参数；用途由服务端固定为 ONLINE。 */
public record OnlineObservabilityQueryRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        String spaceId,
        String configFingerprint,
        String dataIndexVersion
) {
    /** 映射为不依赖 HTTP 的应用层筛选。 */
    public OnlineRetrievalObservabilityService.Filters toFilters() {
        return new OnlineRetrievalObservabilityService.Filters(
                from,
                to,
                spaceId,
                configFingerprint,
                dataIndexVersion
        );
    }
}
