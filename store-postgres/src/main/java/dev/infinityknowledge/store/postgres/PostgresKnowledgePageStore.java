package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.wiki.KnowledgePage;
import dev.infinityknowledge.domain.wiki.KnowledgePageId;
import dev.infinityknowledge.domain.wiki.KnowledgePageRevision;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import dev.infinityknowledge.domain.wiki.PageSourceReference;
import dev.infinityknowledge.spi.wiki.KnowledgePageStore;
import dev.infinityknowledge.spi.wiki.KnowledgePageConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL page draft, provenance and publication adapter. */
public final class PostgresKnowledgePageStore implements KnowledgePageStore {
    private static final String SNAPSHOT_SELECT = """
            SELECT p.id AS page_id, p.tenant_id, p.space_id, p.slug, p.title,
                   p.status, p.latest_revision_id, p.active_revision_id,
                   p.version, p.created_at AS page_created_at,
                   p.updated_at AS page_updated_at,
                   r.id AS revision_id, r.revision_number, r.summary, r.markdown,
                   r.sources_json::text AS sources_json, r.content_hash,
                   r.compiler_version, r.generated_by,
                   r.created_at AS revision_created_at
              FROM knowledge_page p
              JOIN knowledge_page_revision r
                ON r.tenant_id = p.tenant_id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    public PostgresKnowledgePageStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public PageSnapshot saveDraft(DraftCommand command) {
        requireDraft(command);
        return Objects.requireNonNull(transaction.execute(status -> saveDraftInTransaction(command)));
    }

    private PageSnapshot saveDraftInTransaction(DraftCommand command) {
        PageRow page = lockPage(command.tenantId(), command.spaceId(), command.slug())
                .orElseGet(() -> createPage(command));
        Optional<KnowledgePageRevision> existing = findRevision(
                command.tenantId(),
                page.id(),
                command.content().contentHash(),
                command.content().compilerVersion()
        );
        KnowledgePageRevision revision = existing.orElseGet(() -> createRevision(command, page));
        if (revision.id().equals(page.latestRevisionId())
                && page.title().equals(command.title())) {
            return snapshot(page, revision);
        }
        jdbc.update("""
                UPDATE knowledge_page
                   SET title = ?, latest_revision_id = ?, version = version + 1,
                       updated_at = GREATEST(updated_at, ?)
                 WHERE tenant_id = ? AND id = ?
                """,
                command.title(),
                revision.id(),
                databaseTime(command.now()),
                command.tenantId().value(),
                page.id().value()
        );
        return findByIdInternal(command.tenantId(), page.id(), false).orElseThrow();
    }

    @Override
    public PageSnapshot transition(TransitionCommand command) {
        requireTransition(command);
        return Objects.requireNonNull(transaction.execute(status -> {
            PageSnapshot current = findByIdInternal(command.tenantId(), command.pageId(), true)
                    .orElseThrow(() -> new IllegalArgumentException("knowledge page does not exist"));
            if (current.page().version() != command.expectedVersion()) {
                throw new KnowledgePageConflictException("knowledge page version conflict");
            }
            requireAllowedTransition(current.page(), command.targetStatus());
            UUID activeRevisionId = command.targetStatus() == KnowledgePageStatus.PUBLISHED
                    ? current.page().latestRevisionId()
                    : command.targetStatus() == KnowledgePageStatus.ARCHIVED
                            ? null : current.page().activeRevisionId();
            int updated = jdbc.update("""
                    UPDATE knowledge_page
                       SET status = ?, active_revision_id = ?, version = version + 1,
                           updated_at = GREATEST(updated_at, ?)
                     WHERE tenant_id = ? AND id = ? AND version = ?
                    """,
                    command.targetStatus().name(),
                    activeRevisionId,
                    databaseTime(command.now()),
                    command.tenantId().value(),
                    command.pageId().value(),
                    command.expectedVersion()
            );
            if (updated != 1) {
                throw new KnowledgePageConflictException("knowledge page version conflict");
            }
            jdbc.update("""
                    INSERT INTO knowledge_page_review_event (
                        id, tenant_id, page_id, revision_id, from_status,
                        to_status, actor_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    command.tenantId().value(),
                    command.pageId().value(),
                    current.page().latestRevisionId(),
                    current.page().status().name(),
                    command.targetStatus().name(),
                    command.actor().value(),
                    databaseTime(command.now())
            );
            return findByIdInternal(command.tenantId(), command.pageId(), false).orElseThrow();
        }));
    }

    @Override
    public Optional<PageSnapshot> findById(TenantId tenantId, KnowledgePageId pageId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(pageId, "pageId must not be null");
        return findByIdInternal(tenantId, pageId, false);
    }

    @Override
    public Optional<PageSnapshot> findPublished(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String slug
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        return jdbc.query(
                SNAPSHOT_SELECT + """
                       AND r.id = p.active_revision_id
                     WHERE p.tenant_id = ? AND p.space_id = ? AND p.slug = ?
                       AND p.active_revision_id IS NOT NULL
                       AND p.status <> 'ARCHIVED'
                    """,
                this::snapshot,
                tenantId.value(),
                spaceId.value(),
                slug
        ).stream().findFirst();
    }

    @Override
    public List<PageSnapshot> findAll(
            TenantId tenantId,
            Set<KnowledgeSpaceId> spaceIds,
            KnowledgePageStatus status
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceIds, "spaceIds must not be null");
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
                spaceIds.size(),
                "?"
        ));
        String statusClause = status == null ? "" : " AND p.status = ?\n";
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(tenantId.value());
        spaceIds.stream().map(KnowledgeSpaceId::value).sorted().forEach(parameters::add);
        if (status != null) {
            parameters.add(status.name());
        }
        return List.copyOf(jdbc.query(
                SNAPSHOT_SELECT + """
                       AND r.id = p.latest_revision_id
                     WHERE p.tenant_id = ?
                       AND p.space_id IN (%s)
                    """.formatted(placeholders) + statusClause + """
                     ORDER BY p.updated_at DESC, p.id
                    """,
                this::snapshot,
                parameters.toArray()
        ));
    }

    @Override
    public Optional<KnowledgePageRevision> findRevision(
            TenantId tenantId,
            KnowledgePageId pageId,
            UUID revisionId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(pageId, "pageId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        return jdbc.query("""
                SELECT id AS revision_id, revision_number, summary, markdown,
                       sources_json::text AS sources_json, content_hash,
                       compiler_version, generated_by, created_at AS revision_created_at
                  FROM knowledge_page_revision
                 WHERE tenant_id = ? AND page_id = ? AND id = ?
                """, (row, ignored) -> revision(row, pageId),
                tenantId.value(), pageId.value(), revisionId
        ).stream().findFirst();
    }

    @Override
    public List<PageSnapshot> findAffectedBySourceRevision(
            TenantId tenantId,
            UUID sourceRevisionId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(sourceRevisionId, "sourceRevisionId must not be null");
        return List.copyOf(jdbc.query(
                SNAPSHOT_SELECT + """
                       AND r.id = p.latest_revision_id
                     WHERE p.tenant_id = ?
                       AND EXISTS (
                           SELECT 1
                             FROM knowledge_page_revision source_revision,
                                  jsonb_array_elements(source_revision.sources_json) source
                            WHERE source_revision.tenant_id = p.tenant_id
                              AND source_revision.page_id = p.id
                              AND source ->> 'revisionId' = ?
                       )
                     ORDER BY p.updated_at DESC, p.id
                    """,
                this::snapshot,
                tenantId.value(),
                sourceRevisionId.toString()
        ));
    }

    private Optional<PageSnapshot> findByIdInternal(
            TenantId tenantId,
            KnowledgePageId pageId,
            boolean forUpdate
    ) {
        return jdbc.query(
                SNAPSHOT_SELECT + """
                       AND r.id = p.latest_revision_id
                     WHERE p.tenant_id = ? AND p.id = ?
                    """ + (forUpdate ? " FOR UPDATE OF p" : ""),
                this::snapshot,
                tenantId.value(),
                pageId.value()
        ).stream().findFirst();
    }

    private Optional<PageRow> lockPage(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String slug
    ) {
        return jdbc.query("""
                SELECT id, title, status, latest_revision_id, active_revision_id,
                       version, created_at, updated_at
                  FROM knowledge_page
                 WHERE tenant_id = ? AND space_id = ? AND slug = ?
                 FOR UPDATE
                """, (row, ignored) -> new PageRow(
                new KnowledgePageId(row.getObject("id", UUID.class)),
                tenantId,
                spaceId,
                slug,
                row.getString("title"),
                KnowledgePageStatus.valueOf(row.getString("status")),
                row.getObject("latest_revision_id", UUID.class),
                row.getObject("active_revision_id", UUID.class),
                row.getLong("version"),
                instant(row, "created_at"),
                instant(row, "updated_at")
        ), tenantId.value(), spaceId.value(), slug).stream().findFirst();
    }

    private PageRow createPage(DraftCommand command) {
        KnowledgePageId pageId = new KnowledgePageId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO knowledge_page (
                    tenant_id, id, space_id, slug, title, status,
                    latest_revision_id, active_revision_id, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'DRAFT', NULL, NULL, 0, ?, ?)
                """,
                command.tenantId().value(),
                pageId.value(),
                command.spaceId().value(),
                command.slug(),
                command.title(),
                databaseTime(command.now()),
                databaseTime(command.now())
        );
        return new PageRow(
                pageId,
                command.tenantId(),
                command.spaceId(),
                command.slug(),
                command.title(),
                KnowledgePageStatus.DRAFT,
                null,
                null,
                0,
                command.now(),
                command.now()
        );
    }

    private Optional<KnowledgePageRevision> findRevision(
            TenantId tenantId,
            KnowledgePageId pageId,
            String contentHash,
            String compilerVersion
    ) {
        return jdbc.query("""
                SELECT id AS revision_id, revision_number, summary, markdown,
                       sources_json::text AS sources_json,
                       content_hash, compiler_version, generated_by, created_at
                  FROM knowledge_page_revision
                 WHERE tenant_id = ? AND page_id = ?
                   AND content_hash = ? AND compiler_version = ?
                """, (row, ignored) -> new KnowledgePageRevision(
                        row.getObject("revision_id", UUID.class),
                        pageId,
                        row.getLong("revision_number"),
                        row.getString("summary"),
                        row.getString("markdown"),
                        sources(row.getString("sources_json")),
                        row.getString("content_hash"),
                        row.getString("compiler_version"),
                        row.getString("generated_by"),
                        instant(row, "created_at")
                ),
                tenantId.value(), pageId.value(), contentHash, compilerVersion
        ).stream().findFirst();
    }

    private KnowledgePageRevision createRevision(DraftCommand command, PageRow page) {
        long revisionNumber = jdbc.queryForObject("""
                SELECT coalesce(max(revision_number), 0) + 1
                  FROM knowledge_page_revision
                 WHERE tenant_id = ? AND page_id = ?
                """, Long.class, command.tenantId().value(), page.id().value());
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_page_revision (
                    tenant_id, id, page_id, revision_number, summary, markdown,
                    sources_json, content_hash, compiler_version, generated_by,
                    created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """,
                command.tenantId().value(),
                revisionId,
                page.id().value(),
                revisionNumber,
                command.content().summary(),
                command.content().markdown(),
                sourcesJson(command.content().sources()),
                command.content().contentHash(),
                command.content().compilerVersion(),
                command.content().generatedBy(),
                command.actor().value(),
                databaseTime(command.now())
        );
        return new KnowledgePageRevision(
                revisionId,
                page.id(),
                revisionNumber,
                command.content().summary(),
                command.content().markdown(),
                command.content().sources(),
                command.content().contentHash(),
                command.content().compilerVersion(),
                command.content().generatedBy(),
                command.now()
        );
    }

    private PageSnapshot snapshot(ResultSet row, int ignored) throws SQLException {
        KnowledgePageId pageId = new KnowledgePageId(row.getObject("page_id", UUID.class));
        KnowledgePage page = new KnowledgePage(
                pageId,
                new TenantId(row.getString("tenant_id")),
                new KnowledgeSpaceId(row.getString("space_id")),
                row.getString("slug"),
                row.getString("title"),
                KnowledgePageStatus.valueOf(row.getString("status")),
                row.getObject("latest_revision_id", UUID.class),
                row.getObject("active_revision_id", UUID.class),
                row.getLong("version"),
                instant(row, "page_created_at"),
                instant(row, "page_updated_at")
        );
        return new PageSnapshot(page, revision(row, pageId));
    }

    private PageSnapshot snapshot(PageRow page, KnowledgePageRevision revision) {
        return new PageSnapshot(new KnowledgePage(
                page.id(),
                page.tenantId(),
                page.spaceId(),
                page.slug(),
                page.title(),
                page.status(),
                page.latestRevisionId() == null ? revision.id() : page.latestRevisionId(),
                page.activeRevisionId(),
                page.version(),
                page.createdAt(),
                page.updatedAt()
        ), revision);
    }

    private KnowledgePageRevision revision(ResultSet row, KnowledgePageId pageId)
            throws SQLException {
        return new KnowledgePageRevision(
                row.getObject("revision_id", UUID.class),
                pageId,
                row.getLong("revision_number"),
                row.getString("summary"),
                row.getString("markdown"),
                sources(row.getString("sources_json")),
                row.getString("content_hash"),
                row.getString("compiler_version"),
                row.getString("generated_by"),
                instant(row, "revision_created_at")
        );
    }

    private void requireAllowedTransition(KnowledgePage page, KnowledgePageStatus target) {
        boolean allowed = switch (page.status()) {
            case DRAFT -> target == KnowledgePageStatus.IN_REVIEW
                    || target == KnowledgePageStatus.ARCHIVED;
            case IN_REVIEW -> target == KnowledgePageStatus.DRAFT
                    || target == KnowledgePageStatus.PUBLISHED;
            case PUBLISHED -> target == KnowledgePageStatus.IN_REVIEW
                    || target == KnowledgePageStatus.ARCHIVED;
            case ARCHIVED -> target == KnowledgePageStatus.DRAFT;
        };
        if (!allowed) {
            throw new KnowledgePageConflictException(
                    "invalid page transition " + page.status() + " -> " + target
            );
        }
        if (page.status() == KnowledgePageStatus.PUBLISHED
                && target == KnowledgePageStatus.IN_REVIEW
                && Objects.equals(page.latestRevisionId(), page.activeRevisionId())) {
            throw new KnowledgePageConflictException(
                    "published page has no unpublished draft"
            );
        }
    }

    private String sourcesJson(List<PageSourceReference> sources) {
        List<Map<String, Object>> values = sources.stream().map(source -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("documentId", source.documentId().value().toString());
            value.put("revisionId", source.revisionId().toString());
            value.put("chunkId", source.chunkId().toString());
            value.put("sectionPath", source.sectionPath());
            value.put("contentHash", source.contentHash());
            value.put("authority", source.authority());
            return Map.copyOf(value);
        }).toList();
        try {
            return jsonMapper.writeValueAsString(values);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot serialize page provenance", failure);
        }
    }

    private List<PageSourceReference> sources(String json) {
        try {
            List<Map<String, Object>> values = jsonMapper.readValue(
                    json,
                    new TypeReference<List<Map<String, Object>>>() { }
            );
            return values.stream().map(value -> new PageSourceReference(
                    new DocumentId(UUID.fromString((String) value.get("documentId"))),
                    UUID.fromString((String) value.get("revisionId")),
                    UUID.fromString((String) value.get("chunkId")),
                    stringList(value.get("sectionPath")),
                    (String) value.get("contentHash"),
                    ((Number) value.get("authority")).intValue()
            )).toList();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot read persisted page provenance", failure);
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("sectionPath must be a JSON array");
        }
        return list.stream().map(item -> Objects.toString(item, null)).toList();
    }

    private static void requireDraft(DraftCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.tenantId(), "tenantId must not be null");
        Objects.requireNonNull(command.spaceId(), "spaceId must not be null");
        Objects.requireNonNull(command.content(), "content must not be null");
        Objects.requireNonNull(command.actor(), "actor must not be null");
        Objects.requireNonNull(command.now(), "now must not be null");
    }

    private static void requireTransition(TransitionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.tenantId(), "tenantId must not be null");
        Objects.requireNonNull(command.pageId(), "pageId must not be null");
        Objects.requireNonNull(command.targetStatus(), "targetStatus must not be null");
        Objects.requireNonNull(command.actor(), "actor must not be null");
        Objects.requireNonNull(command.now(), "now must not be null");
        if (command.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record PageRow(
            KnowledgePageId id,
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String slug,
            String title,
            KnowledgePageStatus status,
            UUID latestRevisionId,
            UUID activeRevisionId,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
