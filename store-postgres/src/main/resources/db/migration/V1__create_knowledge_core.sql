CREATE TABLE knowledge_tenant (
    id                  varchar(64) PRIMARY KEY,
    display_name        varchar(256) NOT NULL,
    status              varchar(32) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT ck_knowledge_tenant_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE knowledge_principal (
    tenant_id           varchar(64) NOT NULL REFERENCES knowledge_tenant(id),
    principal_id        varchar(128) NOT NULL,
    principal_type      varchar(32) NOT NULL,
    display_name        varchar(256) NOT NULL,
    status              varchar(32) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, principal_id),
    CONSTRAINT ck_knowledge_principal_type
        CHECK (principal_type IN ('USER', 'SERVICE')),
    CONSTRAINT ck_knowledge_principal_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE knowledge_space (
    tenant_id           varchar(64) NOT NULL REFERENCES knowledge_tenant(id),
    id                  varchar(64) NOT NULL,
    name                varchar(256) NOT NULL,
    description         text NOT NULL DEFAULT '',
    status              varchar(32) NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_knowledge_space_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_knowledge_space_version CHECK (version >= 0)
);

CREATE TABLE knowledge_space_acl (
    tenant_id           varchar(64) NOT NULL,
    space_id            varchar(64) NOT NULL,
    subject_type        varchar(32) NOT NULL,
    subject_id          varchar(128) NOT NULL,
    permission          varchar(32) NOT NULL,
    granted_by          varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, space_id, subject_type, subject_id, permission),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_space_acl_subject
        CHECK (subject_type IN ('USER', 'ROLE', 'DEPARTMENT', 'TENANT')),
    CONSTRAINT ck_knowledge_space_acl_permission
        CHECK (permission IN ('READ', 'WRITE', 'ADMIN'))
);

CREATE INDEX ix_knowledge_space_acl_subject
    ON knowledge_space_acl (tenant_id, subject_type, subject_id, permission);

CREATE TABLE connector_instance (
    tenant_id           varchar(64) NOT NULL,
    id                  varchar(128) NOT NULL,
    space_id            varchar(64) NOT NULL,
    connector_type      varchar(64) NOT NULL,
    display_name        varchar(256) NOT NULL,
    config_json         jsonb NOT NULL DEFAULT '{}'::jsonb,
    status              varchar(32) NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    CONSTRAINT ck_connector_instance_status
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR', 'DELETED')),
    CONSTRAINT ck_connector_instance_version CHECK (version >= 0)
);

CREATE TABLE connector_checkpoint (
    tenant_id           varchar(64) NOT NULL,
    connector_id        varchar(128) NOT NULL,
    cursor_json         jsonb NOT NULL DEFAULT '{}'::jsonb,
    snapshot_id         uuid,
    version             bigint NOT NULL DEFAULT 0,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, connector_id),
    FOREIGN KEY (tenant_id, connector_id)
        REFERENCES connector_instance(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_connector_checkpoint_version CHECK (version >= 0)
);

CREATE TABLE connector_sync_run (
    id                  uuid PRIMARY KEY,
    tenant_id           varchar(64) NOT NULL,
    connector_id        varchar(128) NOT NULL,
    snapshot_id         uuid NOT NULL,
    status              varchar(32) NOT NULL,
    records_seen        bigint NOT NULL DEFAULT 0,
    records_changed     bigint NOT NULL DEFAULT 0,
    records_deleted     bigint NOT NULL DEFAULT 0,
    error_code          varchar(128),
    started_at          timestamptz NOT NULL,
    completed_at        timestamptz,
    FOREIGN KEY (tenant_id, connector_id)
        REFERENCES connector_instance(tenant_id, id),
    CONSTRAINT ck_connector_sync_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_connector_sync_run_counts
        CHECK (records_seen >= 0 AND records_changed >= 0 AND records_deleted >= 0)
);

CREATE INDEX ix_connector_sync_run_connector
    ON connector_sync_run (tenant_id, connector_id, started_at DESC);

CREATE TABLE knowledge_document (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    space_id            varchar(64) NOT NULL,
    connector_id        varchar(128) NOT NULL,
    external_id         varchar(512) NOT NULL,
    source_type         varchar(32) NOT NULL,
    source_uri          text NOT NULL,
    title               varchar(512) NOT NULL,
    status              varchar(32) NOT NULL,
    authority           smallint NOT NULL,
    metadata_json       jsonb NOT NULL DEFAULT '{}'::jsonb,
    active_revision_id  uuid,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, connector_id, external_id),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    FOREIGN KEY (tenant_id, connector_id)
        REFERENCES connector_instance(tenant_id, id),
    CONSTRAINT ck_knowledge_document_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_knowledge_document_authority CHECK (authority BETWEEN 0 AND 100),
    CONSTRAINT ck_knowledge_document_version CHECK (version >= 0)
);

CREATE INDEX ix_knowledge_document_space_status
    ON knowledge_document (tenant_id, space_id, status);

CREATE TABLE document_revision (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    document_id         uuid NOT NULL,
    revision_number     bigint NOT NULL,
    content_hash        varchar(128) NOT NULL,
    media_type          varchar(128) NOT NULL,
    language            varchar(32) NOT NULL,
    parser_version      varchar(64) NOT NULL,
    object_uri          text,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, document_id, revision_number),
    UNIQUE (tenant_id, document_id, content_hash),
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_document_revision_number CHECK (revision_number > 0)
);

ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_knowledge_document_active_revision
    FOREIGN KEY (tenant_id, active_revision_id)
    REFERENCES document_revision(tenant_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE knowledge_element (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    revision_id         uuid NOT NULL,
    parent_id           uuid,
    element_type        varchar(32) NOT NULL,
    ordinal             integer NOT NULL,
    section_path_json   jsonb NOT NULL DEFAULT '[]'::jsonb,
    content             text NOT NULL,
    attributes_json     jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES document_revision(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, parent_id)
        REFERENCES knowledge_element(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_element_ordinal CHECK (ordinal >= 0)
);

CREATE INDEX ix_knowledge_element_revision
    ON knowledge_element (tenant_id, revision_id, ordinal);

CREATE TABLE knowledge_chunk (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    space_id            varchar(64) NOT NULL,
    document_id         uuid NOT NULL,
    revision_id         uuid NOT NULL,
    ordinal             integer NOT NULL,
    section_path_json   jsonb NOT NULL DEFAULT '[]'::jsonb,
    element_ids_json    jsonb NOT NULL DEFAULT '[]'::jsonb,
    content             text NOT NULL,
    content_hash        varchar(128) NOT NULL,
    metadata_json       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, revision_id, ordinal),
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES document_revision(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    CONSTRAINT ck_knowledge_chunk_ordinal CHECK (ordinal >= 0)
);

CREATE INDEX ix_knowledge_chunk_document
    ON knowledge_chunk (tenant_id, document_id, revision_id, ordinal);

CREATE TABLE index_generation (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    space_id            varchar(64) NOT NULL,
    status              varchar(32) NOT NULL,
    embedding_provider  varchar(64) NOT NULL,
    embedding_model     varchar(128) NOT NULL,
    embedding_dimensions integer NOT NULL,
    normalizer_version  varchar(64) NOT NULL,
    chunker_version     varchar(64) NOT NULL,
    configuration_hash  varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    published_at        timestamptz,
    retired_at          timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    CONSTRAINT ck_index_generation_status
        CHECK (status IN ('BUILDING', 'READY', 'ACTIVE', 'FAILED', 'RETIRED')),
    CONSTRAINT ck_index_generation_dimensions
        CHECK (embedding_dimensions > 0)
);

CREATE UNIQUE INDEX ux_index_generation_active
    ON index_generation (tenant_id, space_id)
    WHERE status = 'ACTIVE';

CREATE TABLE document_index_projection (
    tenant_id           varchar(64) NOT NULL,
    generation_id       uuid NOT NULL,
    document_id         uuid NOT NULL,
    revision_id         uuid NOT NULL,
    keyword_status      varchar(32) NOT NULL,
    vector_status       varchar(32) NOT NULL,
    graph_status        varchar(32) NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, generation_id, document_id),
    FOREIGN KEY (tenant_id, generation_id)
        REFERENCES index_generation(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES document_revision(tenant_id, id),
    CONSTRAINT ck_document_index_keyword_status
        CHECK (keyword_status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_document_index_vector_status
        CHECK (vector_status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_document_index_graph_status
        CHECK (graph_status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);

CREATE TABLE retrieval_trace (
    id                  uuid PRIMARY KEY,
    request_id          uuid NOT NULL UNIQUE,
    tenant_id           varchar(64) NOT NULL REFERENCES knowledge_tenant(id),
    principal_id        varchar(128) NOT NULL,
    query_hash          varchar(128) NOT NULL,
    total_duration_ms   bigint NOT NULL,
    result_count        integer NOT NULL,
    created_at          timestamptz NOT NULL,
    CONSTRAINT ck_retrieval_trace_duration CHECK (total_duration_ms >= 0),
    CONSTRAINT ck_retrieval_trace_count CHECK (result_count >= 0)
);

CREATE INDEX ix_retrieval_trace_tenant_created
    ON retrieval_trace (tenant_id, created_at DESC);

CREATE TABLE retrieval_trace_step (
    trace_id            uuid NOT NULL REFERENCES retrieval_trace(id) ON DELETE CASCADE,
    ordinal             integer NOT NULL,
    step_name           varchar(64) NOT NULL,
    duration_ms         bigint NOT NULL,
    input_count         integer NOT NULL,
    output_count        integer NOT NULL,
    status              varchar(32) NOT NULL,
    PRIMARY KEY (trace_id, ordinal),
    CONSTRAINT ck_retrieval_trace_step_values
        CHECK (ordinal >= 0 AND duration_ms >= 0
            AND input_count >= 0 AND output_count >= 0)
);

CREATE TABLE evaluation_dataset (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    name                varchar(256) NOT NULL,
    version             bigint NOT NULL,
    status              varchar(32) NOT NULL,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name, version),
    CONSTRAINT ck_evaluation_dataset_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_evaluation_dataset_version CHECK (version > 0)
);

CREATE TABLE evaluation_case (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    dataset_id          uuid NOT NULL,
    query_text          text NOT NULL,
    expected_documents_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    expected_chunks_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    labels_json         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, dataset_id)
        REFERENCES evaluation_dataset(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE evaluation_run (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    dataset_id          uuid NOT NULL,
    generation_id       uuid NOT NULL,
    status              varchar(32) NOT NULL,
    configuration_json  jsonb NOT NULL,
    metrics_json        jsonb,
    started_at          timestamptz NOT NULL,
    completed_at        timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, dataset_id)
        REFERENCES evaluation_dataset(tenant_id, id),
    FOREIGN KEY (tenant_id, generation_id)
        REFERENCES index_generation(tenant_id, id),
    CONSTRAINT ck_evaluation_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

