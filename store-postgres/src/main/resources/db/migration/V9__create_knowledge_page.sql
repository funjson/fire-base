CREATE TABLE knowledge_page (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    space_id            varchar(64) NOT NULL,
    slug                varchar(256) NOT NULL,
    title               varchar(512) NOT NULL,
    status              varchar(32) NOT NULL,
    latest_revision_id  uuid,
    active_revision_id  uuid,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, space_id, slug),
    FOREIGN KEY (tenant_id, space_id)
        REFERENCES knowledge_space(tenant_id, id),
    CONSTRAINT ck_knowledge_page_status
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_page_version CHECK (version >= 0),
    CONSTRAINT ck_knowledge_page_active_revision
        CHECK (status <> 'PUBLISHED' OR active_revision_id IS NOT NULL)
);

CREATE TABLE knowledge_page_revision (
    tenant_id           varchar(64) NOT NULL,
    id                  uuid NOT NULL,
    page_id             uuid NOT NULL,
    revision_number     bigint NOT NULL,
    summary             text NOT NULL,
    markdown            text NOT NULL,
    sources_json        jsonb NOT NULL,
    content_hash        varchar(128) NOT NULL,
    compiler_version    varchar(128) NOT NULL,
    generated_by        varchar(128) NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, page_id, revision_number),
    UNIQUE (tenant_id, page_id, content_hash, compiler_version),
    FOREIGN KEY (tenant_id, page_id)
        REFERENCES knowledge_page(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_page_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_knowledge_page_revision_sources
        CHECK (jsonb_typeof(sources_json) = 'array' AND jsonb_array_length(sources_json) > 0)
);

ALTER TABLE knowledge_page
    ADD CONSTRAINT fk_knowledge_page_latest_revision
    FOREIGN KEY (tenant_id, latest_revision_id)
    REFERENCES knowledge_page_revision(tenant_id, id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE knowledge_page
    ADD CONSTRAINT fk_knowledge_page_active_revision
    FOREIGN KEY (tenant_id, active_revision_id)
    REFERENCES knowledge_page_revision(tenant_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE knowledge_page_review_event (
    id                  uuid PRIMARY KEY,
    tenant_id           varchar(64) NOT NULL,
    page_id             uuid NOT NULL,
    revision_id         uuid NOT NULL,
    from_status         varchar(32) NOT NULL,
    to_status           varchar(32) NOT NULL,
    actor_id            varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, page_id)
        REFERENCES knowledge_page(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES knowledge_page_revision(tenant_id, id),
    CONSTRAINT ck_knowledge_page_review_from
        CHECK (from_status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_page_review_to
        CHECK (to_status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX ix_knowledge_page_status
    ON knowledge_page (tenant_id, space_id, status, updated_at DESC);

CREATE INDEX ix_knowledge_page_source_revision
    ON knowledge_page_revision USING gin (sources_json jsonb_path_ops);
