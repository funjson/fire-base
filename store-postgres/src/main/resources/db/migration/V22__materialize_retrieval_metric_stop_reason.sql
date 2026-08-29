-- 停止原因是正式的低基数 Dashboard 维度，物化后无需扫描 dimensions_json。
ALTER TABLE retrieval_metric_fact
    ADD COLUMN stop_reason varchar(64),
    ADD CONSTRAINT ck_retrieval_metric_fact_stop_reason
        CHECK (
            stop_reason IS NULL OR stop_reason IN (
                'SUFFICIENCY_THRESHOLD_REACHED', 'COVERAGE_DISABLED',
                'EVIDENCE_REQUIREMENTS_MISSING', 'COVERAGE_CHECK_FAILED',
                'RETRIEVAL_BUDGET_EXHAUSTED', 'OPTIMIZATION_CHAIN_EXHAUSTED',
                'NO_APPLICABLE_OPTIMIZATION_NODE', 'TECHNICAL_FAILURE'
            )
        );

CREATE INDEX ix_retrieval_metric_fact_stop_reason_time
    ON retrieval_metric_fact (tenant_id, metric_key, stop_reason, time_slice)
    WHERE stop_reason IS NOT NULL;
