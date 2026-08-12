CREATE TABLE mutation_audit_event (
    id                  uuid PRIMARY KEY,
    tenant_id           varchar(64) NOT NULL,
    principal_id        varchar(128) NOT NULL,
    request_id          uuid NOT NULL,
    http_method         varchar(16) NOT NULL,
    route_pattern       varchar(256) NOT NULL,
    action              varchar(320) NOT NULL,
    response_status     integer NOT NULL,
    outcome             varchar(16) NOT NULL,
    duration_ms         bigint NOT NULL,
    created_at          timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, principal_id)
        REFERENCES knowledge_principal(tenant_id, principal_id),
    CONSTRAINT ck_mutation_audit_method
        CHECK (http_method IN ('POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT ck_mutation_audit_status
        CHECK (response_status BETWEEN 100 AND 599),
    CONSTRAINT ck_mutation_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_mutation_audit_duration
        CHECK (duration_ms >= 0)
);

CREATE INDEX ix_mutation_audit_tenant_created
    ON mutation_audit_event (tenant_id, created_at DESC, id DESC);

CREATE INDEX ix_mutation_audit_tenant_request
    ON mutation_audit_event (tenant_id, request_id);
