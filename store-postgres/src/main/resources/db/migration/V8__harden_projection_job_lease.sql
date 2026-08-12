ALTER TABLE projection_job
    ADD COLUMN lease_token bigint NOT NULL DEFAULT 0,
    ADD COLUMN requeue_requested boolean NOT NULL DEFAULT false;

ALTER TABLE projection_job
    ADD CONSTRAINT ck_projection_job_lease_token CHECK (lease_token >= 0);

COMMENT ON COLUMN projection_job.lease_token IS
    'Monotonic fencing token incremented for every lease claim.';

COMMENT ON COLUMN projection_job.requeue_requested IS
    'Set when source metadata changes while the current projection is running.';
