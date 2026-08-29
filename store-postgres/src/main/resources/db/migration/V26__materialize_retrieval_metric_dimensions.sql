-- v4 指标读取只聚合权威事实；这些低基数维度物化后不需要在 Dashboard 查询中扫描 JSON。
-- 历史事实继续保留，无法无损确定的新维度保持 NULL，不从旧 payload 推断。
ALTER TABLE retrieval_metric_fact
    ADD COLUMN technical_status varchar(32),
    ADD COLUMN terminal_status varchar(32),
    ADD COLUMN stage varchar(64),
    ADD COLUMN visit_index integer,
    ADD COLUMN channel varchar(32),
    ADD COLUMN chain_node varchar(128),
    ADD COLUMN coverage_status varchar(32);

UPDATE retrieval_metric_fact
   SET technical_status = dimensions_json ->> 'TECHNICAL_STATUS',
       terminal_status = dimensions_json ->> 'TERMINAL_STATUS',
       stage = dimensions_json ->> 'STAGE',
       visit_index = (dimensions_json ->> 'VISIT_INDEX')::integer,
       channel = dimensions_json ->> 'CHANNEL',
       chain_node = dimensions_json ->> 'CHAIN_NODE',
       coverage_status = dimensions_json ->> 'COVERAGE_STATUS'
 WHERE metric_definition_version = 4;

ALTER TABLE retrieval_metric_fact
    ADD CONSTRAINT ck_retrieval_metric_fact_technical_status
        CHECK (
            technical_status IS NULL OR technical_status IN (
                'UNOBSERVED', 'STARTED', 'SUCCEEDED', 'DEGRADED', 'SKIPPED',
                'FAILED', 'TIMED_OUT', 'CANCELLED', 'REJECTED', 'NOT_CONFIGURED'
            )
        ),
    ADD CONSTRAINT ck_retrieval_metric_fact_terminal_status
        CHECK (
            terminal_status IS NULL OR terminal_status IN (
                'SUFFICIENT', 'INSUFFICIENT', 'NOT_EVALUATED',
                'EVIDENCE_REQUIREMENTS_MISSING', 'CHECK_FAILED', 'TECHNICAL_FAILED'
            )
        ),
    ADD CONSTRAINT ck_retrieval_metric_fact_stage
        CHECK (
            stage IS NULL OR stage IN (
                'EXECUTION_STARTED', 'SPACE_ROUTING', 'CONFIGURATION_RESOLVED',
                'QUERY_ANALYSIS', 'QUERY_PLANNING', 'RETRIEVAL_PLAN',
                'RETRIEVAL_BRANCH', 'FUSION', 'RERANK', 'COVERAGE_CHECK',
                'CHAIN_NODE_EVALUATED', 'CHAIN_NODE_COMPLETED', 'SPACE_CHANGED',
                'EVIDENCE_BUILD', 'EXECUTION_TERMINAL', 'STAGE_FAILURE'
            )
        ),
    ADD CONSTRAINT ck_retrieval_metric_fact_visit_index
        CHECK (visit_index IS NULL OR visit_index >= 0),
    ADD CONSTRAINT ck_retrieval_metric_fact_channel
        CHECK (channel IS NULL OR channel IN ('KEYWORD', 'VECTOR', 'GRAPH', 'PAGE')),
    ADD CONSTRAINT ck_retrieval_metric_fact_coverage_status
        CHECK (
            coverage_status IS NULL OR coverage_status IN (
                'CONTINUE', 'SUFFICIENT', 'INSUFFICIENT', 'NOT_EVALUATED',
                'EVIDENCE_REQUIREMENTS_MISSING', 'CHECK_FAILED'
            )
        ),
    ADD CONSTRAINT ck_retrieval_metric_fact_v4_status
        CHECK (metric_definition_version <> 4 OR technical_status IS NOT NULL);

CREATE INDEX ix_retrieval_metric_fact_online_v4_metric
    ON retrieval_metric_fact (
        tenant_id, purpose, fact_scope, metric_definition_version,
        metric_key, aggregation, observed_at DESC
    )
    WHERE fact_type = 'RUNTIME' AND metric_definition_version = 4;

CREATE INDEX ix_retrieval_metric_fact_online_v4_stage
    ON retrieval_metric_fact (
        tenant_id, purpose, stage, metric_key, aggregation, observed_at DESC
    )
    WHERE fact_type = 'RUNTIME'
      AND fact_scope = 'EVENT'
      AND metric_definition_version = 4;
