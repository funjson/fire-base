-- API upload is a space-scoped source. Create one stable connector per space before
-- moving legacy documents away from the tenant-wide api-upload connector.
INSERT INTO connector_instance
    (tenant_id, id, space_id, connector_type, display_name,
     config_json, status, version, created_at, updated_at)
SELECT s.tenant_id,
       'api-upload:' || s.id,
       s.id,
       'API',
       'API Upload',
       '{}'::jsonb,
       'ACTIVE',
       0,
       s.created_at,
       s.updated_at
FROM knowledge_space s
ON CONFLICT (tenant_id, id) DO UPDATE
SET status = 'ACTIVE', updated_at = EXCLUDED.updated_at;

UPDATE knowledge_document
SET connector_id = 'api-upload:' || space_id
WHERE connector_id = 'api-upload';

-- Previous releases could write chunks and jobs with the requested space while the
-- stable document still belonged to another space. The document is authoritative
-- during migration; ambiguous lost source identities cannot be reconstructed.
UPDATE knowledge_chunk c
SET space_id = d.space_id
FROM knowledge_document d
WHERE c.tenant_id = d.tenant_id
  AND c.document_id = d.id
  AND c.space_id <> d.space_id;

UPDATE projection_job j
SET space_id = d.space_id
FROM knowledge_document d
WHERE j.tenant_id = d.tenant_id
  AND j.document_id = d.id
  AND j.space_id <> d.space_id;

UPDATE connector_instance
SET status = 'DELETED', updated_at = now()
WHERE id = 'api-upload';

ALTER TABLE knowledge_document
    DROP CONSTRAINT IF EXISTS knowledge_document_tenant_id_connector_id_external_id_key;

ALTER TABLE knowledge_document
    ADD CONSTRAINT uq_knowledge_document_source_identity
    UNIQUE (tenant_id, space_id, connector_id, external_id);

ALTER TABLE connector_instance
    ADD CONSTRAINT uq_connector_instance_space_identity
    UNIQUE (tenant_id, id, space_id);

ALTER TABLE knowledge_document
    ADD CONSTRAINT uq_knowledge_document_space_identity
    UNIQUE (tenant_id, id, space_id);

ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_knowledge_document_connector_space
    FOREIGN KEY (tenant_id, connector_id, space_id)
    REFERENCES connector_instance(tenant_id, id, space_id);

ALTER TABLE knowledge_chunk
    ADD CONSTRAINT fk_knowledge_chunk_document_space
    FOREIGN KEY (tenant_id, document_id, space_id)
    REFERENCES knowledge_document(tenant_id, id, space_id) ON DELETE CASCADE;

ALTER TABLE projection_job
    ADD CONSTRAINT fk_projection_job_document_space
    FOREIGN KEY (tenant_id, document_id, space_id)
    REFERENCES knowledge_document(tenant_id, id, space_id) ON DELETE CASCADE;
