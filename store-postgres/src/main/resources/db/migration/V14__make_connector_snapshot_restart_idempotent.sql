ALTER TABLE connector_sync_run
    ADD COLUMN snapshot_restart_token bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_connector_sync_run_snapshot_restart_token CHECK (
        snapshot_restart_token >= 0 AND snapshot_restart_token <= lease_token
    );

COMMENT ON COLUMN connector_sync_run.snapshot_restart_token IS
    'Lease token that last reset this run full-snapshot staging and checkpoint.';
