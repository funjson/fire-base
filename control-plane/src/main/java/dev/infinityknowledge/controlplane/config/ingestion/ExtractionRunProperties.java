package dev.infinityknowledge.controlplane.config.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/** 多文件测试与正式摄取任务共用的 HTTP 批量边界和后台接管窗口。 */
@ConfigurationProperties(prefix = "infinity.knowledge.ingestion.extraction-runs")
public record ExtractionRunProperties(
        int maximumFiles,
        long maximumTotalBytes,
        int listLimit,
        Duration staleAfter,
        Duration receivingTimeout
) {

    /** 限制单请求文件数、列表大小和失联 Worker 接管时间。 */
    public ExtractionRunProperties {
        if (maximumFiles < 1 || maximumFiles > 100) {
            throw new IllegalArgumentException("maximumFiles must be between 1 and 100");
        }
        if (maximumTotalBytes < 1L) {
            throw new IllegalArgumentException("maximumTotalBytes must be positive");
        }
        if (listLimit < 1 || listLimit > 200) {
            throw new IllegalArgumentException("listLimit must be between 1 and 200");
        }
        Objects.requireNonNull(staleAfter, "staleAfter must not be null");
        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        Objects.requireNonNull(receivingTimeout, "receivingTimeout must not be null");
        if (receivingTimeout.isZero() || receivingTimeout.isNegative()) {
            throw new IllegalArgumentException("receivingTimeout must be positive");
        }
    }
}
