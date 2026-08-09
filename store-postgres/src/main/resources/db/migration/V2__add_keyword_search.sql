ALTER TABLE knowledge_chunk
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE INDEX ix_knowledge_chunk_search_vector
    ON knowledge_chunk USING gin (search_vector);

CREATE INDEX ix_knowledge_document_active_lookup
    ON knowledge_document (tenant_id, space_id, status, active_revision_id);
