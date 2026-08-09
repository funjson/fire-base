CREATE TABLE projection_job (
    id                  uuid PRIMARY KEY,
    tenant_id           varchar(64) NOT NULL,
    space_id            varchar(64) NOT NULL,
    document_id         uuid NOT NULL,
    revision_id         uuid NOT NULL,
    projection_type     varchar(32) NOT NULL,
    status              varchar(32) NOT NULL,
    attempt_count       integer NOT NULL DEFAULT 0,
    available_at        timestamptz NOT NULL,
    lease_owner         varchar(128),
    lease_until         timestamptz,
    last_error_code     varchar(128),
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    completed_at        timestamptz,
    UNIQUE (tenant_id, revision_id, projection_type),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES document_revision(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_projection_job_type
        CHECK (projection_type IN ('VECTOR', 'KEYWORD', 'GRAPH')),
    CONSTRAINT ck_projection_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'DEAD')),
    CONSTRAINT ck_projection_job_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_projection_job_lease CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX ix_projection_job_claim
    ON projection_job (status, available_at, created_at)
    WHERE status IN ('PENDING', 'RETRY', 'RUNNING');

CREATE INDEX ix_projection_job_document
    ON projection_job (tenant_id, document_id, created_at DESC);
