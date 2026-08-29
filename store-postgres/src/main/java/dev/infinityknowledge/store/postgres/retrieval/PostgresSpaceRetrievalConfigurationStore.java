package dev.infinityknowledge.store.postgres.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用 PostgreSQL 不可变版本表和 Space 当前指针保存检索配置。
 *
 * <p>追加时先锁定目标 {@code knowledge_space} 行，使首次创建和后续切换都按
 * 单个 Space 串行化；版本插入和指针切换处于同一事务，不会暴露半完成状态。</p>
 */
public final class PostgresSpaceRetrievalConfigurationStore
        implements SpaceRetrievalConfigurationStore {
    private static final String SELECT_COLUMNS = """
            version.tenant_id, version.space_id, version.revision,
            version.configuration_json::text AS configuration_json,
            version.fingerprint, version.created_by, version.created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    /**
     * 创建事务化 Space 检索配置适配器。
     *
     * @param jdbc 命名参数 JDBC 模板
     * @param transaction 事务模板
     */
    public PostgresSpaceRetrievalConfigurationStore(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transaction
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(
                transaction,
                "transaction must not be null"
        );
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public Optional<SpaceRetrievalConfiguration> findCurrent(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        List<SpaceRetrievalConfiguration> values = jdbc.query(
                "SELECT " + SELECT_COLUMNS + """
                  FROM space_retrieval_configuration_current current_config
                  JOIN space_retrieval_configuration_version version
                    ON version.tenant_id = current_config.tenant_id
                   AND version.space_id = current_config.space_id
                   AND version.revision = current_config.current_revision
                 WHERE current_config.tenant_id = :tenantId
                   AND current_config.space_id = :spaceId
                """,
                parameters(tenantId, spaceId),
                (row, number) -> configuration(row)
        );
        return values.stream().findFirst();
    }

    @Override
    public Optional<SpaceRetrievalConfiguration> findRevision(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            long revision
    ) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        List<SpaceRetrievalConfiguration> values = jdbc.query(
                "SELECT " + SELECT_COLUMNS + """
                  FROM space_retrieval_configuration_version version
                 WHERE version.tenant_id = :tenantId
                   AND version.space_id = :spaceId
                   AND version.revision = :revision
                """,
                parameters(tenantId, spaceId).addValue("revision", revision),
                (row, number) -> configuration(row)
        );
        return values.stream().findFirst();
    }

    @Override
    public List<SpaceRetrievalConfiguration> history(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            int limit
    ) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return List.copyOf(jdbc.query(
                "SELECT " + SELECT_COLUMNS + """
                  FROM space_retrieval_configuration_version version
                 WHERE version.tenant_id = :tenantId
                   AND version.space_id = :spaceId
                 ORDER BY version.revision DESC
                 LIMIT :limit
                """,
                parameters(tenantId, spaceId).addValue("limit", limit),
                (row, number) -> configuration(row)
        ));
    }

    @Override
    public ActivationOutcome appendAndActivate(
            SpaceRetrievalConfiguration configuration,
            long expectedCurrentRevision
    ) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (expectedCurrentRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedCurrentRevision must be non-negative"
            );
        }
        if (configuration.revision() != expectedCurrentRevision + 1L) {
            throw new IllegalArgumentException(
                    "new revision must equal expectedCurrentRevision plus one"
            );
        }
        ActivationOutcome outcome = transaction.execute(status ->
                appendLocked(configuration, expectedCurrentRevision)
        );
        return Objects.requireNonNull(outcome, "transaction must return an activation outcome");
    }

    /** 在已开启的事务中锁定 Space、校验当前修订并完成追加和切换。 */
    private ActivationOutcome appendLocked(
            SpaceRetrievalConfiguration configuration,
            long expectedCurrentRevision
    ) {
        MapSqlParameterSource parameters = parameters(
                configuration.tenantId(),
                configuration.spaceId()
        );
        List<Integer> spaces = jdbc.query("""
                SELECT 1
                  FROM knowledge_space
                 WHERE tenant_id = :tenantId
                   AND id = :spaceId
                   AND status = 'ACTIVE'
                 FOR UPDATE
                """, parameters, (row, number) -> row.getInt(1));
        if (spaces.isEmpty()) {
            throw new IllegalArgumentException("active knowledge space does not exist");
        }

        Long currentRevision = currentRevision(parameters);
        if (currentRevision != null && currentRevision == configuration.revision()) {
            return findRevision(
                    configuration.tenantId(),
                    configuration.spaceId(),
                    configuration.revision()
            ).filter(existing -> existing.fingerprint().equals(configuration.fingerprint()))
                    .map(ignored -> ActivationOutcome.ALREADY_ACTIVE)
                    .orElse(ActivationOutcome.REVISION_CONFLICT);
        }
        long actualCurrentRevision = currentRevision == null ? 0L : currentRevision;
        if (actualCurrentRevision != expectedCurrentRevision) {
            return ActivationOutcome.REVISION_CONFLICT;
        }

        MapSqlParameterSource write = parameters
                .addValue("revision", configuration.revision())
                .addValue("configurationJson", json(configuration.configuration()))
                .addValue("fingerprint", configuration.fingerprint())
                .addValue("createdBy", configuration.createdBy().value())
                .addValue(
                        "createdAt",
                        OffsetDateTime.ofInstant(
                                configuration.createdAt(),
                                java.time.ZoneOffset.UTC
                        )
                );
        int inserted = jdbc.update("""
                INSERT INTO space_retrieval_configuration_version
                    (tenant_id, space_id, revision, configuration_json,
                     fingerprint, created_by, created_at)
                VALUES
                    (:tenantId, :spaceId, :revision,
                     CAST(:configurationJson AS jsonb), :fingerprint,
                     :createdBy, :createdAt)
                ON CONFLICT (tenant_id, space_id, revision) DO NOTHING
                """, write);
        if (inserted != 1) {
            return ActivationOutcome.REVISION_CONFLICT;
        }

        if (currentRevision == null) {
            jdbc.update("""
                    INSERT INTO space_retrieval_configuration_current
                        (tenant_id, space_id, current_revision,
                         activated_by, activated_at)
                    VALUES
                        (:tenantId, :spaceId, :revision, :createdBy, :createdAt)
                    """, write);
        } else {
            int switched = jdbc.update("""
                    UPDATE space_retrieval_configuration_current
                       SET current_revision = :revision,
                           activated_by = :createdBy,
                           activated_at = :createdAt
                     WHERE tenant_id = :tenantId
                       AND space_id = :spaceId
                       AND current_revision = :expectedCurrentRevision
                    """, write.addValue(
                    "expectedCurrentRevision",
                    expectedCurrentRevision
            ));
            if (switched != 1) {
                throw new IllegalStateException(
                        "space retrieval configuration pointer changed while space was locked"
                );
            }
        }
        return ActivationOutcome.ACTIVATED;
    }

    private Long currentRevision(MapSqlParameterSource parameters) {
        List<Long> revisions = jdbc.query("""
                SELECT current_revision
                  FROM space_retrieval_configuration_current
                 WHERE tenant_id = :tenantId
                   AND space_id = :spaceId
                """, parameters, (row, number) -> row.getLong("current_revision"));
        return revisions.stream().findFirst().orElse(null);
    }

    private SpaceRetrievalConfiguration configuration(ResultSet row) throws SQLException {
        RetrievalConfiguration value;
        try {
            value = jsonMapper.readValue(
                    row.getString("configuration_json"),
                    RetrievalConfiguration.class
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "stored retrieval configuration is invalid",
                    failure
            );
        }
        return new SpaceRetrievalConfiguration(
                new TenantId(row.getString("tenant_id")),
                new KnowledgeSpaceId(row.getString("space_id")),
                row.getLong("revision"),
                value,
                row.getString("fingerprint"),
                new PrincipalId(row.getString("created_by")),
                row.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private String json(RetrievalConfiguration configuration) {
        try {
            return jsonMapper.writeValueAsString(configuration);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "retrieval configuration cannot be serialized",
                    failure
            );
        }
    }

    private static MapSqlParameterSource parameters(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("spaceId", spaceId.value());
    }
}
