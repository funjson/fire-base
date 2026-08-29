package dev.infinityknowledge.ingestion.extraction;

import dev.infinityknowledge.ingestion.chunking.ChunkingDiagnostics;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Parse、Clean、Chunk 各阶段可安全进入 Trace 和评测结果的聚合诊断。
 *
 * <p>诊断只记录能力标识、原因码和数量，不记录原文、模型向量或第三方原始响应。</p>
 *
 * @param parse Parse 阶段诊断
 * @param cleaning Clean 阶段诊断
 * @param chunking Chunk 阶段诊断
 */
public record ExtractionDiagnostics(
        ParseStage parse,
        CleaningStage cleaning,
        ChunkStage chunking
) {

    /** 保证每个阶段都返回可观测结果。 */
    public ExtractionDiagnostics {
        Objects.requireNonNull(parse, "parse must not be null");
        Objects.requireNonNull(cleaning, "cleaning must not be null");
        Objects.requireNonNull(chunking, "chunking must not be null");
    }

    /**
     * Parse 阶段的非敏感聚合结果。
     *
     * @param parserId 实际执行的稳定 Parser 标识
     * @param mediaType Parser 识别后的规范媒体类型
     * @param producedElements Parser 产出的结构元素数
     * @param duration 本阶段耗时
     */
    public record ParseStage(
            String parserId,
            String mediaType,
            int producedElements,
            Duration duration
    ) {

        /** 拒绝无法定位实现或没有产生结构元素的成功结果。 */
        public ParseStage {
            Objects.requireNonNull(parserId, "parserId must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            if (parserId.isBlank() || mediaType.isBlank()) {
                throw new IllegalArgumentException("parserId and mediaType must not be blank");
            }
            if (producedElements < 0) {
                throw new IllegalArgumentException("producedElements must be non-negative");
            }
            duration = nonNegative(duration, "parse duration");
        }
    }

    /**
     * Clean 阶段的元素去向统计。
     *
     * @param indexableElements 允许进入 Chunker 的元素数
     * @param metadataOnlyElements 仅供治理持久化的元素数
     * @param reasonCodeCounts 各稳定清洗原因码的命中数
     * @param duration 本阶段耗时
     */
    public record CleaningStage(
            int indexableElements,
            int metadataOnlyElements,
            Map<String, Integer> reasonCodeCounts,
            Duration duration
    ) {

        /** 防御性保存审计计数，不重复执行清洗规则校验。 */
        public CleaningStage {
            if (indexableElements < 0 || metadataOnlyElements < 0) {
                throw new IllegalArgumentException("element counts must be non-negative");
            }
            reasonCodeCounts = Map.copyOf(Objects.requireNonNull(
                    reasonCodeCounts,
                    "reasonCodeCounts must not be null"
            ));
            duration = nonNegative(duration, "cleaning duration");
        }
    }

    /**
     * Chunk 阶段的边界决策统计和耗时。
     *
     * @param diagnostics 不含正文的 Chunk 聚合诊断
     * @param duration 本阶段耗时
     */
    public record ChunkStage(
            ChunkingDiagnostics diagnostics,
            Duration duration
    ) {

        /** 保证成功阶段同时提供聚合诊断和非负耗时。 */
        public ChunkStage {
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            duration = nonNegative(duration, "chunking duration");
        }
    }

    private static Duration nonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
