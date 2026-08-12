CREATE TABLE document_source_object (
    tenant_id           varchar(64) NOT NULL,
    revision_id         uuid NOT NULL,
    storage_id          varchar(256) NOT NULL,
    original_file_name  varchar(512) NOT NULL,
    media_type          varchar(128) NOT NULL,
    content_length      bigint NOT NULL,
    checksum_sha256     char(64) NOT NULL,
    stored_at           timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, revision_id),
    UNIQUE (tenant_id, storage_id),
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES document_revision(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_document_source_object_length CHECK (content_length >= 0),
    CONSTRAINT ck_document_source_object_checksum
        CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);
