-- execution 投影保存业务终态而不是终态事件的技术状态，并物化在线总览所需的低基数事实。
ALTER TABLE retrieval_execution_observation
    ADD COLUMN technical_status varchar(32),
    ADD COLUMN degraded boolean,
    ADD COLUMN retrieval_attempt_count integer,
    ADD COLUMN result_count integer;

-- V20-V24 曾把 event.status 写入 terminal_status，把 event.reason_code 写入终止原因；
-- 原始终态 payload 是权威事实源，因此可无损修复历史投影。
UPDATE retrieval_execution_observation execution
   SET terminal_status = terminal.payload_json ->> 'terminalStatus',
       terminal_reason_code = terminal.payload_json ->> 'stopReason',
       technical_status = terminal.status,
       degraded = (terminal.payload_json ->> 'degraded')::boolean,
       retrieval_attempt_count =
           (terminal.payload_json ->> 'retrievalAttemptCount')::integer,
       result_count = (terminal.payload_json ->> 'resultCount')::integer
  FROM retrieval_observation_event terminal
 WHERE terminal.tenant_id = execution.tenant_id
   AND terminal.execution_id = execution.execution_id
   AND terminal.stage = 'EXECUTION_TERMINAL';

ALTER TABLE retrieval_execution_observation
    ADD CONSTRAINT ck_retrieval_execution_terminal_materialization
        CHECK (
            (
                terminal_status IS NULL
                AND terminal_reason_code IS NULL
                AND technical_status IS NULL
                AND degraded IS NULL
                AND retrieval_attempt_count IS NULL
                AND result_count IS NULL
            )
            OR
            (
                terminal_status IN (
                    'SUFFICIENT', 'INSUFFICIENT', 'NOT_EVALUATED',
                    'EVIDENCE_REQUIREMENTS_MISSING', 'CHECK_FAILED', 'TECHNICAL_FAILED'
                )
                AND terminal_reason_code IN (
                    'SUFFICIENCY_THRESHOLD_REACHED', 'COVERAGE_DISABLED',
                    'EVIDENCE_REQUIREMENTS_MISSING', 'COVERAGE_CHECK_FAILED',
                    'RETRIEVAL_BUDGET_EXHAUSTED', 'OPTIMIZATION_CHAIN_EXHAUSTED',
                    'NO_APPLICABLE_OPTIMIZATION_NODE', 'TECHNICAL_FAILURE'
                )
                AND technical_status IN (
                    'SUCCEEDED', 'DEGRADED', 'FAILED', 'TIMED_OUT',
                    'CANCELLED', 'REJECTED'
                )
                AND degraded IS NOT NULL
                AND retrieval_attempt_count >= 0
                AND result_count >= 0
            )
        );

CREATE INDEX ix_retrieval_execution_online_window
    ON retrieval_execution_observation (tenant_id, purpose, first_event_at DESC);
