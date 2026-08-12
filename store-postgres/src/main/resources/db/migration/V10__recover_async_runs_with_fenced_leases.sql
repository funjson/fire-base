ALTER TABLE connector_sync_run
    ADD COLUMN principal_json jsonb NOT NULL DEFAULT
        '{"principalId":"system-recovery","roleIds":["knowledge-admin"],"departmentIds":[],"systemPrincipal":true}'::jsonb,
    ADD COLUMN lease_owner varchar(160),
    ADD COLUMN lease_token bigint NOT NULL DEFAULT 0,
    ADD COLUMN lease_until timestamptz;

ALTER TABLE connector_sync_run
    DROP CONSTRAINT ck_connector_sync_run_status;

UPDATE connector_sync_run
   SET status = 'PENDING'
 WHERE status = 'RUNNING';

DROP INDEX ux_connector_sync_run_single_flight;

ALTER TABLE connector_sync_run
    ADD CONSTRAINT ck_connector_sync_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    ADD CONSTRAINT ck_connector_sync_run_lease_token CHECK (lease_token >= 0),
    ADD CONSTRAINT ck_connector_sync_run_lease_state CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    );

CREATE UNIQUE INDEX ux_connector_sync_run_single_flight
    ON connector_sync_run (tenant_id, connector_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX ix_connector_sync_run_claim
    ON connector_sync_run (status, lease_until, started_at)
    WHERE status IN ('PENDING', 'RUNNING');

ALTER TABLE evaluation_run
    ADD COLUMN principal_json jsonb NOT NULL DEFAULT
        '{"principalId":"system-recovery","roleIds":["knowledge-admin"],"departmentIds":[],"systemPrincipal":true}'::jsonb,
    ADD COLUMN case_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN lease_owner varchar(160),
    ADD COLUMN lease_token bigint NOT NULL DEFAULT 0,
    ADD COLUMN lease_until timestamptz;

ALTER TABLE evaluation_run
    DROP CONSTRAINT ck_evaluation_run_status;

UPDATE evaluation_run run
   SET case_ids_json = cases.case_ids
  FROM (
        SELECT dataset.tenant_id,
               dataset.id AS dataset_id,
               coalesce(
                   jsonb_agg(evaluation_case.id ORDER BY evaluation_case.created_at, evaluation_case.id)
                       FILTER (WHERE evaluation_case.id IS NOT NULL),
                   '[]'::jsonb
               ) AS case_ids
          FROM evaluation_dataset dataset
          LEFT JOIN evaluation_case
            ON evaluation_case.tenant_id = dataset.tenant_id
           AND evaluation_case.dataset_id = dataset.id
         GROUP BY dataset.tenant_id, dataset.id
  ) cases
 WHERE run.tenant_id = cases.tenant_id
   AND run.dataset_id = cases.dataset_id;

UPDATE evaluation_run
   SET principal_json = jsonb_build_object(
           'principalId', requested_by,
           'roleIds', jsonb_build_array('knowledge-admin'),
           'departmentIds', '[]'::jsonb,
           'systemPrincipal', true
       )
 WHERE status = 'RUNNING';

UPDATE evaluation_run
   SET status = 'FAILED',
       error_code = 'EVALUATION_CASE_SNAPSHOT_MISSING',
       completed_at = current_timestamp
 WHERE status = 'RUNNING'
   AND jsonb_array_length(case_ids_json) = 0;

UPDATE evaluation_run
   SET status = 'PENDING'
 WHERE status = 'RUNNING';

ALTER TABLE evaluation_run
    ADD CONSTRAINT ck_evaluation_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    ADD CONSTRAINT ck_evaluation_run_lease_token CHECK (lease_token >= 0),
    ADD CONSTRAINT ck_evaluation_run_lease_state CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    );

CREATE INDEX ix_evaluation_run_claim
    ON evaluation_run (status, lease_until, started_at)
    WHERE status IN ('PENDING', 'RUNNING');
