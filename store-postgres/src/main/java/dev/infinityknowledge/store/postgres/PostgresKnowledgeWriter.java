package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 使用 PostgreSQL 事务写入文档、修订、结构元素与 Chunk。
 */
public final class PostgresKnowledgeWriter implements KnowledgeWriter {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final Set<ProjectionType> enabledProjections;

    /**
     * 创建 PostgreSQL 知识写入器。
     *
     * @param jdbc JDBC 模板
     * @param transaction 事务模板
     * @param jsonMapper JSON Mapper
     */
    public PostgresKnowledgeWriter(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            JsonMapper jsonMapper
    ) {
        this(jdbc, transaction, jsonMapper, Set.of());
    }

    /**
     * Creates a writer with transactional projection-job emission.
     *
     * @param jdbc JDBC template
     * @param transaction transaction template
     * @param jsonMapper JSON mapper
     * @param vectorProjectionEnabled whether new revisions enqueue vector projection
     */
    public PostgresKnowledgeWriter(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            JsonMapper jsonMapper,
            boolean vectorProjectionEnabled
    ) {
        this(
                jdbc,
                transaction,
                jsonMapper,
                vectorProjectionEnabled ? Set.of(ProjectionType.VECTOR) : Set.of()
        );
    }

    /**
     * Creates a writer that transactionally emits every configured projection type.
     */
    public PostgresKnowledgeWriter(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            JsonMapper jsonMapper,
            Set<ProjectionType> enabledProjections
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.enabledProjections = Set.copyOf(
                Objects.requireNonNull(enabledProjections, "enabledProjections must not be null")
        );
    }

    /**
     * 在一个事务中幂等发布修订，只有最后一步才切换活动修订。
     *
     * @param batch 写入批次
     * @return 写入结果
     */
    @Override
    public KnowledgeWriteResult write(KnowledgeWriteBatch batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        KnowledgeWriteResult result = transaction.execute(status -> writeTransaction(batch));
        if (result == null) {
            throw new IllegalStateException("knowledge write transaction returned no result");
        }
        return result;
    }

    /**
     * 执行事务内部写入。
     */
    private KnowledgeWriteResult writeTransaction(KnowledgeWriteBatch batch) {
        var document = batch.document();
        var revision = batch.revision();
        String tenantId = document.tenantId().value();
        lockDocumentIdentity(tenantId, document.id().value());
        Optional<StoredDocumentState> storedDocument = lockAndInspectDocument(batch);
        upsertDocument(batch);
        Optional<StoredRevision> storedRevision = findRevision(
                tenantId,
                document.id().value(),
                revision
        );
        if (storedRevision.isPresent()) {
            StoredRevision stored = storedRevision.orElseThrow();
            boolean documentChanged = storedDocument
                    .map(StoredDocumentState::projectionChanged)
                    .orElse(false);
            if (!stored.active()) {
                activateRevision(batch, stored.id());
            } else if (documentChanged) {
                incrementDocumentVersion(batch);
            }
            boolean changed = !stored.active() || documentChanged;
            if (changed) {
                enabledProjections.forEach(type -> enqueueProjection(batch, stored.id(), type));
            }
            return new KnowledgeWriteResult(
                    document.id(),
                    stored.id(),
                    changed,
                    stored.chunkCount()
            );
        }
        long revisionNumber = nextRevisionNumber(tenantId, document.id().value());
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, object_uri, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                tenantId,
                revision.id(),
                document.id().value(),
                revisionNumber,
                revision.contentHash(),
                revision.mediaType(),
                revision.language(),
                revision.parserVersion(),
                revision.createdAt().atOffset(ZoneOffset.UTC)
        );
        for (KnowledgeElement element : batch.elements()) {
            insertElement(tenantId, element);
        }
        for (KnowledgeChunk chunk : batch.chunks()) {
            insertChunk(chunk);
        }
        activateRevision(batch, revision.id());
        enabledProjections.forEach(type -> enqueueProjection(batch, revision.id(), type));
        return new KnowledgeWriteResult(
                document.id(),
                revision.id(),
                true,
                batch.chunks().size()
        );
    }

    /**
     * 新文档尚无可锁定的数据行，先按租户和文档标识取得事务级 advisory lock。
     */
    private void lockDocumentIdentity(String tenantId, UUID documentId) {
        String lockIdentity = tenantId + ":" + documentId;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
            )) {
                statement.setString(1, lockIdentity);
                statement.execute();
            }
            return null;
        });
    }

    /**
     * Emits the vector job in the same transaction as revision publication.
     */
    private void enqueueProjection(
            KnowledgeWriteBatch batch,
            UUID revisionId,
            ProjectionType projectionType
    ) {
        var document = batch.document();
        String jobIdentity = String.join(
                ":",
                document.tenantId().value(),
                revisionId.toString(),
                projectionType.name()
        );
        UUID jobId = UUID.nameUUIDFromBytes(jobIdentity.getBytes(StandardCharsets.UTF_8));
        var now = document.updatedAt().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO projection_job
                    (id, tenant_id, space_id, document_id, revision_id,
                     projection_type, status, attempt_count, available_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT (tenant_id, revision_id, projection_type) DO UPDATE
                SET status = 'PENDING',
                    attempt_count = 0,
                    available_at = EXCLUDED.available_at,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    updated_at = EXCLUDED.updated_at,
                    completed_at = NULL
                WHERE projection_job.status IN ('SUCCEEDED', 'DEAD')
                """,
                jobId,
                document.tenantId().value(),
                document.spaceId().value(),
                document.id().value(),
                revisionId,
                projectionType.name(),
                now,
                now,
                now
        );
    }

    /**
     * 锁定稳定文档行并校验来源身份，后续修订号分配都处于同一事务。
     */
    private Optional<StoredDocumentState> lockAndInspectDocument(
            KnowledgeWriteBatch batch
    ) {
        var document = batch.document();
        String metadataJson = json(document.metadata());
        List<StoredDocumentState> matches = jdbc.query("""
                SELECT space_id, connector_id, external_id, source_type,
                       (title IS DISTINCT FROM ?
                        OR source_uri IS DISTINCT FROM ?
                        OR authority IS DISTINCT FROM ?
                        OR metadata_json IS DISTINCT FROM ?::jsonb)
                       AS projection_changed
                FROM knowledge_document
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """,
                (row, rowNumber) -> new StoredDocumentState(
                        row.getString("space_id"),
                        row.getString("connector_id"),
                        row.getString("external_id"),
                        row.getString("source_type"),
                        row.getBoolean("projection_changed")
                ),
                document.title(),
                document.source().uri(),
                document.authority(),
                metadataJson,
                document.tenantId().value(),
                document.id().value()
        );
        if (matches.size() > 1) {
            throw new IllegalStateException("document id resolved multiple rows");
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        var source = document.source();
        var stored = matches.getFirst();
        if (!document.spaceId().value().equals(stored.spaceId())
                || !source.connectorId().equals(stored.connectorId())
                || !source.externalId().equals(stored.externalId())
                || !source.type().name().equals(stored.sourceType())) {
            throw new IllegalStateException("document identity cannot move across source or space");
        }
        return Optional.of(stored);
    }

    /**
     * 查找相同内容与处理契约的不可变历史修订，并判断它是否已经活动。
     */
    private Optional<StoredRevision> findRevision(
            String tenantId,
            UUID documentId,
            DocumentRevision revision
    ) {
        List<StoredRevision> matches = jdbc.query("""
                SELECT r.id,
                       (d.active_revision_id = r.id AND d.status = 'ACTIVE') AS active,
                       count(c.id) AS chunk_count
                FROM document_revision r
                JOIN knowledge_document d
                  ON d.tenant_id = r.tenant_id AND d.id = r.document_id
                LEFT JOIN knowledge_chunk c
                  ON c.tenant_id = r.tenant_id AND c.revision_id = r.id
                WHERE r.tenant_id = ?
                  AND r.document_id = ?
                  AND r.content_hash = ?
                  AND r.media_type = ?
                  AND r.language = ?
                  AND r.parser_version = ?
                GROUP BY r.id, d.active_revision_id, d.status
                """,
                (row, rowNumber) -> new StoredRevision(
                        row.getObject("id", UUID.class),
                        row.getBoolean("active"),
                        row.getInt("chunk_count")
                ),
                tenantId,
                documentId,
                revision.contentHash(),
                revision.mediaType(),
                revision.language(),
                revision.parserVersion()
        );
        if (matches.size() > 1) {
            throw new IllegalStateException("revision fingerprint resolved multiple revisions");
        }
        return matches.stream().findFirst();
    }

    /**
     * 在文档行锁内分配最终单调修订号。
     */
    private long nextRevisionNumber(String tenantId, UUID documentId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(max(revision_number), 0) + 1
                FROM document_revision
                WHERE tenant_id = ? AND document_id = ?
                """, Long.class, tenantId, documentId);
        if (value == null) {
            throw new IllegalStateException("revision sequence query returned no value");
        }
        return value;
    }

    /**
     * 原子切换活动修订；历史回切和新修订发布共用同一路径。
     */
    private void activateRevision(KnowledgeWriteBatch batch, UUID revisionId) {
        var document = batch.document();
        int updated = jdbc.update("""
                UPDATE knowledge_document
                SET active_revision_id = ?, status = 'ACTIVE', version = version + 1,
                    updated_at = GREATEST(updated_at, ?)
                WHERE tenant_id = ? AND id = ?
                """,
                revisionId,
                document.updatedAt().atOffset(ZoneOffset.UTC),
                document.tenantId().value(),
                document.id().value()
        );
        if (updated != 1) {
            throw new IllegalStateException("active revision update did not find the document");
        }
    }

    /**
     * Increments the aggregate version when projected document fields change without
     * switching the active immutable revision.
     */
    private void incrementDocumentVersion(KnowledgeWriteBatch batch) {
        var document = batch.document();
        int updated = jdbc.update("""
                UPDATE knowledge_document
                SET version = version + 1,
                    updated_at = GREATEST(updated_at, ?)
                WHERE tenant_id = ? AND id = ?
                """,
                document.updatedAt().atOffset(ZoneOffset.UTC),
                document.tenantId().value(),
                document.id().value()
        );
        if (updated != 1) {
            throw new IllegalStateException("document version update did not find the document");
        }
    }

    /**
     * 新建或更新稳定文档聚合。
     */
    private void upsertDocument(KnowledgeWriteBatch batch) {
        var document = batch.document();
        var source = document.source();
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id, source_type,
                     source_uri, title, status, authority, metadata_json,
                     active_revision_id, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, NULL, 0, ?, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET title = EXCLUDED.title,
                    source_uri = EXCLUDED.source_uri,
                    authority = EXCLUDED.authority,
                    metadata_json = EXCLUDED.metadata_json,
                    updated_at = GREATEST(
                        knowledge_document.updated_at,
                        EXCLUDED.updated_at
                    )
                WHERE knowledge_document.title IS DISTINCT FROM EXCLUDED.title
                   OR knowledge_document.source_uri IS DISTINCT FROM EXCLUDED.source_uri
                   OR knowledge_document.authority IS DISTINCT FROM EXCLUDED.authority
                   OR knowledge_document.metadata_json IS DISTINCT FROM EXCLUDED.metadata_json
                """,
                document.tenantId().value(),
                document.id().value(),
                document.spaceId().value(),
                source.connectorId(),
                source.externalId(),
                source.type().name(),
                source.uri(),
                document.title(),
                document.status().name(),
                document.authority(),
                json(document.metadata()),
                document.createdAt().atOffset(ZoneOffset.UTC),
                document.updatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    /**
     * 写入一个结构元素。
     */
    private void insertElement(String tenantId, KnowledgeElement element) {
        jdbc.update("""
                INSERT INTO knowledge_element
                    (tenant_id, id, revision_id, parent_id, element_type,
                     ordinal, section_path_json, content, attributes_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)
                """,
                tenantId,
                element.id(),
                element.revisionId(),
                element.parentId(),
                element.type().name(),
                element.ordinal(),
                json(element.sectionPath()),
                element.content(),
                json(element.attributes())
        );
    }

    /**
     * 写入一个检索 Chunk。
     */
    private void insertChunk(KnowledgeChunk chunk) {
        jdbc.update("""
                INSERT INTO knowledge_chunk
                    (tenant_id, id, space_id, document_id, revision_id, ordinal,
                     section_path_json, element_ids_json, content, content_hash,
                     metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, now())
                """,
                chunk.tenantId().value(),
                chunk.id(),
                chunk.spaceId().value(),
                chunk.documentId().value(),
                chunk.revisionId(),
                chunk.ordinal(),
                json(chunk.sectionPath()),
                json(chunk.elementIds()),
                chunk.content(),
                chunk.contentHash(),
                json(chunk.metadata())
        );
    }

    /**
     * 序列化受控领域数据，不在异常中暴露正文。
     */
    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException serializationFailure) {
            throw new IllegalStateException("failed to serialize knowledge metadata");
        }
    }

    private record StoredDocumentState(
            String spaceId,
            String connectorId,
            String externalId,
            String sourceType,
            boolean projectionChanged
    ) {
    }

    private record StoredRevision(UUID id, boolean active, int chunkCount) {
    }
}
