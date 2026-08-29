package dev.infinityknowledge.evaluation.observation.query;

import dev.infinityknowledge.domain.identity.TenantId;

import java.util.Optional;
import java.util.UUID;

/**
 * 按请求读取检索观测报告的技术中立只读端口。
 *
 * <p>租户是查询条件而不是返回后再过滤的标签；实现必须在数据源内同时使用
 * {@code tenantId + requestId}，并在一个 request 存在多次执行时返回开始时间最新的
 * execution。</p>
 */
@FunctionalInterface
public interface RetrievalObservationReportReader {

    /** 返回租户内指定请求的最新 execution 报告。 */
    Optional<RetrievalObservationReport> findLatestExecution(
            TenantId tenantId,
            UUID requestId
    );
}
