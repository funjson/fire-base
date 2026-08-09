-- A revision is defined by normalized content and the parser contract that produced
-- its immutable elements/chunks. Reusing only content_hash would keep stale language
-- metadata or stale chunks after a parser upgrade.
ALTER TABLE document_revision
    DROP CONSTRAINT IF EXISTS document_revision_tenant_id_document_id_content_hash_key;

ALTER TABLE document_revision
    ADD CONSTRAINT uq_document_revision_fingerprint
    UNIQUE (
        tenant_id,
        document_id,
        content_hash,
        media_type,
        language,
        parser_version
    );
