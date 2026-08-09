WITH duplicate_running AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, connector_id
               ORDER BY started_at DESC, id DESC
           ) AS position
      FROM connector_sync_run
     WHERE status = 'RUNNING'
)
UPDATE connector_sync_run run
   SET status = 'CANCELLED',
       error_code = 'SUPERSEDED_BY_SINGLE_FLIGHT',
       completed_at = current_timestamp
  FROM duplicate_running duplicate
 WHERE run.id = duplicate.id
   AND duplicate.position > 1;

CREATE UNIQUE INDEX ux_connector_sync_run_single_flight
    ON connector_sync_run (tenant_id, connector_id)
    WHERE status = 'RUNNING';
