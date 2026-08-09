ALTER TABLE evaluation_dataset
    ADD COLUMN description text NOT NULL DEFAULT '';

ALTER TABLE evaluation_case
    ADD COLUMN space_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN top_k integer NOT NULL DEFAULT 8;

ALTER TABLE evaluation_case
    ADD CONSTRAINT ck_evaluation_case_top_k CHECK (top_k BETWEEN 1 AND 100);

ALTER TABLE evaluation_run
    ALTER COLUMN generation_id DROP NOT NULL;

ALTER TABLE evaluation_run
    ADD COLUMN requested_by varchar(128) NOT NULL DEFAULT 'system',
    ADD COLUMN case_count integer NOT NULL DEFAULT 0,
    ADD COLUMN failed_case_count integer NOT NULL DEFAULT 0,
    ADD COLUMN error_code varchar(128);

ALTER TABLE evaluation_run
    ADD CONSTRAINT ck_evaluation_run_counts
        CHECK (case_count >= 0 AND failed_case_count BETWEEN 0 AND case_count);

CREATE TABLE evaluation_case_result (
    tenant_id           varchar(64) NOT NULL,
    run_id              uuid NOT NULL,
    case_id             uuid NOT NULL,
    trace_id            uuid,
    status              varchar(32) NOT NULL,
    hit                 boolean NOT NULL,
    recall_at_k         double precision NOT NULL,
    reciprocal_rank     double precision NOT NULL,
    ndcg_at_k           double precision NOT NULL,
    result_count        integer NOT NULL,
    duration_ms         bigint NOT NULL,
    error_code          varchar(128),
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, run_id, case_id),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES evaluation_run(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, case_id)
        REFERENCES evaluation_case(tenant_id, id),
    CONSTRAINT ck_evaluation_case_result_status
        CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_evaluation_case_result_metrics
        CHECK (
            recall_at_k BETWEEN 0 AND 1
            AND reciprocal_rank BETWEEN 0 AND 1
            AND ndcg_at_k BETWEEN 0 AND 1
            AND result_count >= 0
            AND duration_ms >= 0
        )
);

CREATE INDEX ix_evaluation_case_result_run
    ON evaluation_case_result (tenant_id, run_id, status, case_id);
