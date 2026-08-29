package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;

import java.util.List;
import java.util.Objects;

/**
 * 把 Writer 的真实投影配置转换为当前 API 兼容的向量状态字段。
 *
 * <p>该类型只负责兼容旧响应；投影实际排队仍完全由 PostgreSQL Writer 事务完成。</p>
 *
 * @param vectorStatus 旧 API 的向量投影状态
 * @param warnings 稳定警告码
 */
public record ProjectionSchedulingView(String vectorStatus, List<String> warnings) {

    /**
     * 根据本次写入结果构造兼容视图，禁止通过 Bean 是否存在猜测状态。
     */
    public static ProjectionSchedulingView from(KnowledgeWriteResult result) {
        Objects.requireNonNull(result, "result must not be null");
        boolean enabled = result.configuredProjectionTypes().contains(ProjectionType.VECTOR);
        return new ProjectionSchedulingView(
                enabled ? result.changed() ? "QUEUED" : "UNCHANGED" : "SKIPPED",
                enabled ? List.of() : List.of("VECTOR_PROJECTION_DISABLED")
        );
    }

    /**
     * 复制警告列表，保持响应不可变。
     */
    public ProjectionSchedulingView {
        Objects.requireNonNull(vectorStatus, "vectorStatus must not be null");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
    }
}
