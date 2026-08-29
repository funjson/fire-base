-- 支撑控制台按租户和 requestId 下钻最新 execution，避免观测表增长后全表扫描。
CREATE INDEX ix_retrieval_execution_observation_request
    ON retrieval_execution_observation (
        tenant_id,
        request_id,
        first_event_at DESC
    ) INCLUDE (execution_id);
