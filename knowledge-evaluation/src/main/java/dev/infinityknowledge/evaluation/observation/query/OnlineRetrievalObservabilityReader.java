package dev.infinityknowledge.evaluation.observation.query;

import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * 在线检索统计与诊断的技术中立只读端口。
 *
 * <p>实现必须把用途固定为 ONLINE，并在数据源内应用租户及全部授权 Space 条件。</p>
 */
public interface OnlineRetrievalObservabilityReader {

    /** 返回窗口总览和固定粒度趋势。 */
    OnlineRetrievalOverview overview(OnlineRetrievalObservabilityQuery query);

    /** 返回逐阶段、分支、融合、精排、Coverage 和 Chain 诊断。 */
    OnlineRetrievalStageDiagnostics stages(OnlineRetrievalObservabilityQuery query);

    /** 返回窗口内的安全执行记录。 */
    RetrievalExecutionPage executions(
            OnlineRetrievalObservabilityQuery query,
            ExecutionPageRequest pageRequest
    );

    /** 执行记录分页和有限终态筛选。 */
    record ExecutionPageRequest(
            int page,
            int size,
            Optional<RetrievalTerminalStatus> terminalStatus,
            Optional<RetrievalStopReason> stopReason
    ) {
        public static final int MAXIMUM_PAGE_SIZE = 100;

        /** 限制页大小，终态筛选保持枚举类型。 */
        public ExecutionPageRequest {
            if (page < 0 || size < 1 || size > MAXIMUM_PAGE_SIZE) {
                throw new IllegalArgumentException("execution page request is invalid");
            }
            terminalStatus = Objects.requireNonNull(
                    terminalStatus,
                    "terminalStatus must not be null"
            );
            stopReason = Objects.requireNonNull(stopReason, "stopReason must not be null");
        }
    }
}
