CREATE TABLE connector_manifest (
    tenant_id      varchar(64) NOT NULL,
    connector_id   varchar(128) NOT NULL,
    space_id       varchar(64) NOT NULL,
    external_id    varchar(512) NOT NULL,
    document_id    uuid NOT NULL,
    content_hash   varchar(128) NOT NULL,
    source_uri     text NOT NULL,
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, connector_id, external_id),
    FOREIGN KEY (tenant_id, connector_id, space_id)
        REFERENCES connector_instance(tenant_id, id, space_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, document_id, space_id)
        REFERENCES knowledge_document(tenant_id, id, space_id) ON DELETE CASCADE
);

CREATE INDEX ix_connector_manifest_document
    ON connector_manifest (tenant_id, document_id);

-- Seed the authoritative baseline for vaults synchronized before manifest support.
-- Without this backfill, a file removed before the first post-upgrade scan could
-- remain ACTIVE because it would have no previous manifest row to reconcile.
INSERT INTO connector_manifest
    (tenant_id, connector_id, space_id, external_id, document_id,
     content_hash, source_uri, updated_at)
SELECT document.tenant_id,
       document.connector_id,
       document.space_id,
       document.external_id,
       document.id,
       revision.content_hash,
       document.source_uri,
       document.updated_at
  FROM knowledge_document document
  JOIN connector_instance connector
    ON connector.tenant_id = document.tenant_id
   AND connector.id = document.connector_id
   AND connector.connector_type = 'OBSIDIAN'
  JOIN document_revision revision
    ON revision.tenant_id = document.tenant_id
   AND revision.id = document.active_revision_id
 WHERE document.status = 'ACTIVE'
ON CONFLICT (tenant_id, connector_id, external_id) DO NOTHING;

CREATE TABLE connector_snapshot_manifest (
    tenant_id      varchar(64) NOT NULL,
    connector_id   varchar(128) NOT NULL,
    space_id       varchar(64) NOT NULL,
    run_id         uuid NOT NULL REFERENCES connector_sync_run(id) ON DELETE CASCADE,
    snapshot_id    uuid NOT NULL,
    external_id    varchar(512) NOT NULL,
    document_id    uuid NOT NULL,
    content_hash   varchar(128) NOT NULL,
    source_uri     text NOT NULL,
    observed_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, connector_id, snapshot_id, external_id),
    FOREIGN KEY (tenant_id, connector_id, space_id)
        REFERENCES connector_instance(tenant_id, id, space_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, document_id, space_id)
        REFERENCES knowledge_document(tenant_id, id, space_id) ON DELETE CASCADE
);

CREATE INDEX ix_connector_snapshot_manifest_run
    ON connector_snapshot_manifest (run_id);
