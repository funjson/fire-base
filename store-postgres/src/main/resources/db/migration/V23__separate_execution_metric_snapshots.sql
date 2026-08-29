-- 执行完整性、请求分母和最终 Coverage 结论是可替换快照，不是逐事件追加事实。
ALTER TABLE retrieval_metric_fact
    ADD COLUMN fact_scope varchar(16) NOT NULL DEFAULT 'EVENT',
    ADD CONSTRAINT ck_retrieval_metric_fact_scope
        CHECK (
            fact_scope IN ('EVENT', 'EXECUTION')
            AND (fact_scope = 'EVENT' OR fact_type = 'RUNTIME')
        );

-- 旧口径会让一次执行产生多条完整性/中间 Coverage 事实，必须在启用新口径前清除。
DELETE FROM retrieval_metric_fact
 WHERE fact_type = 'RUNTIME'
   AND metric_key IN (
     'retrieval.observation.complete',
     'retrieval.request.count',
     'retrieval.coverage.sufficient'
 );

CREATE UNIQUE INDEX ux_retrieval_metric_fact_execution_snapshot
    ON retrieval_metric_fact (
        tenant_id, execution_id, metric_key,
        metric_definition_version, aggregation
    )
    WHERE fact_scope = 'EXECUTION';
