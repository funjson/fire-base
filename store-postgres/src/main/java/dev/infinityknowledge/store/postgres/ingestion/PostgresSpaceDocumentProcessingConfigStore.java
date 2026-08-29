package dev.infinityknowledge.store.postgres.ingestion;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 使用 PostgreSQL 保存 Space 创建时固化的 Parser、清洗与 Chunker 配置。
 *
 * <p>配置只允许在 Space 创建事务中插入一次，此后 Adapter 仅提供读取能力。
 * 数据库主键和 {@code ON CONFLICT DO NOTHING} 共同阻止任何隐式覆盖。</p>
 */
public final class PostgresSpaceDocumentProcessingConfigStore
        implements SpaceDocumentProcessingConfigStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    /** 创建不可变空间文档处理配置存储。 */
    public PostgresSpaceDocumentProcessingConfigStore(
            NamedParameterJdbcTemplate jdbc
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public Optional<SpaceDocumentProcessingConfig> find(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        var parameters = spaceParameters(tenantId, spaceId);
        List<SpaceDocumentProcessingConfig> configs = jdbc.query("""
                SELECT parser_selections_json,
                       cleaning_header_action, cleaning_footer_action,
                       cleaning_page_number_action, cleaning_watermark_action,
                       cleaning_front_matter_action,
                       chunker_provider_id, tokenizer_id,
                       minimum_tokens, target_tokens, maximum_tokens, overlap_tokens,
                       chunker_provider_config_json,
                       pipeline_contract, normalizer_schema_contract,
                       parser_contracts_json, cleaner_contract, chunker_contract,
                       processing_contract_fingerprint,
                       version, updated_by, updated_at
                  FROM space_document_processing_config
                 WHERE tenant_id = :tenantId
                   AND space_id = :spaceId
                """, parameters, (row, number) -> config(tenantId, spaceId, row));
        return configs.stream().findFirst();
    }

    @Override
    public boolean activeSpaceExists(TenantId tenantId, KnowledgeSpaceId spaceId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM knowledge_space
                     WHERE tenant_id = :tenantId
                       AND id = :spaceId
                       AND status = 'ACTIVE'
                )
                """, spaceParameters(tenantId, spaceId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public CreateOutcome createImmutable(SpaceDocumentProcessingConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.version() != 0L) {
            throw new IllegalArgumentException(
                    "new document processing config version must be 0"
            );
        }
        if (config.updatedBy() == null) {
            throw new IllegalArgumentException(
                    "new document processing config must declare updatedBy"
            );
        }
        DocumentProcessingContract contract = Objects.requireNonNull(
                config.processingContract(),
                "new document processing config must contain processingContract"
        );
        ChunkerConfiguration chunker = config.chunker();
        CleaningConfiguration cleaning = config.cleaning();
        MapSqlParameterSource parameters = spaceParameters(
                config.tenantId(),
                config.spaceId()
        )
                .addValue("parserSelections", json(config.parserSelections()))
                .addValue("cleaningHeader", cleaning.header().name())
                .addValue("cleaningFooter", cleaning.footer().name())
                .addValue("cleaningPageNumber", cleaning.pageNumber().name())
                .addValue("cleaningWatermark", cleaning.watermark().name())
                .addValue("cleaningFrontMatter", cleaning.frontMatter().name())
                .addValue("providerId", chunker.providerId())
                .addValue("tokenizerId", chunker.tokenizerId())
                .addValue("minimumTokens", chunker.minimumTokens())
                .addValue("targetTokens", chunker.targetTokens())
                .addValue("maximumTokens", chunker.maximumTokens())
                .addValue("overlapTokens", chunker.overlapTokens())
                .addValue(
                        "providerConfigurationJson",
                        chunker.providerConfigurationJson()
                )
                .addValue("pipelineContract", contract.pipelineContract())
                .addValue(
                        "normalizerSchemaContract",
                        contract.normalizerSchemaContract()
                )
                .addValue("parserContracts", json(contract.parserContracts()))
                .addValue("cleanerContract", contract.cleanerContract())
                .addValue("chunkerContract", contract.chunkerContract())
                .addValue("processingContractFingerprint", contract.fingerprint())
                .addValue("updatedBy", config.updatedBy().value());
        int created = jdbc.update("""
                INSERT INTO space_document_processing_config
                    (tenant_id, space_id, parser_selections_json,
                     cleaning_header_action, cleaning_footer_action,
                     cleaning_page_number_action, cleaning_watermark_action,
                     cleaning_front_matter_action,
                     chunker_provider_id, tokenizer_id,
                     minimum_tokens, target_tokens, maximum_tokens, overlap_tokens,
                     chunker_provider_config_json,
                     pipeline_contract, normalizer_schema_contract,
                     parser_contracts_json, cleaner_contract, chunker_contract,
                     processing_contract_fingerprint,
                     version, updated_by, created_at, updated_at)
                VALUES
                    (:tenantId, :spaceId, CAST(:parserSelections AS jsonb),
                     :cleaningHeader, :cleaningFooter, :cleaningPageNumber,
                     :cleaningWatermark, :cleaningFrontMatter,
                     :providerId, :tokenizerId,
                     :minimumTokens, :targetTokens, :maximumTokens, :overlapTokens,
                     CAST(:providerConfigurationJson AS jsonb),
                     :pipelineContract, :normalizerSchemaContract,
                     CAST(:parserContracts AS jsonb), :cleanerContract,
                     :chunkerContract, :processingContractFingerprint,
                     1, :updatedBy, clock_timestamp(), clock_timestamp())
                ON CONFLICT (tenant_id, space_id) DO NOTHING
                """, parameters);
        return created == 1 ? CreateOutcome.CREATED : CreateOutcome.ALREADY_EXISTS;
    }

    private SpaceDocumentProcessingConfig config(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ResultSet row
    ) throws SQLException {
        return new SpaceDocumentProcessingConfig(
                tenantId,
                spaceId,
                parserSelections(row.getString("parser_selections_json")),
                new CleaningConfiguration(
                        CleaningAction.valueOf(row.getString("cleaning_header_action")),
                        CleaningAction.valueOf(row.getString("cleaning_footer_action")),
                        CleaningAction.valueOf(row.getString("cleaning_page_number_action")),
                        CleaningAction.valueOf(row.getString("cleaning_watermark_action")),
                        CleaningAction.valueOf(row.getString("cleaning_front_matter_action"))
                ),
                new ChunkerConfiguration(
                        row.getString("chunker_provider_id"),
                        row.getString("tokenizer_id"),
                        row.getInt("minimum_tokens"),
                        row.getInt("target_tokens"),
                        row.getInt("maximum_tokens"),
                        row.getInt("overlap_tokens"),
                        canonicalProviderConfiguration(
                                row.getString("chunker_provider_config_json")
                        )
                ),
                new DocumentProcessingContract(
                        row.getString("pipeline_contract"),
                        row.getString("normalizer_schema_contract"),
                        parserSelections(row.getString("parser_contracts_json")),
                        row.getString("cleaner_contract"),
                        row.getString("chunker_contract"),
                        row.getString("processing_contract_fingerprint")
                ),
                row.getLong("version"),
                new PrincipalId(row.getString("updated_by")),
                row.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private Map<String, String> parserSelections(String json) {
        try {
            return jsonMapper.readValue(
                    json,
                    new TypeReference<Map<String, String>>() { }
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "stored parser selections are invalid",
                    failure
            );
        }
    }

    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "space document processing config cannot be serialized",
                    failure
            );
        }
    }

    /** PostgreSQL jsonb 读取后重新 canonical 化，避免格式影响处理指纹。 */
    private String canonicalProviderConfiguration(String value) {
        try {
            Object decoded = jsonMapper.readValue(value, Object.class);
            if (!(decoded instanceof Map<?, ?>)) {
                throw new IllegalStateException(
                        "stored chunker provider configuration is not a JSON object"
                );
            }
            return jsonMapper.writeValueAsString(canonicalJsonValue(decoded));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "stored chunker provider configuration is invalid",
                    failure
            );
        }
    }

    /** 对 JSON Object 递归排序键，同时保持数组顺序和标量值不变。 */
    private static Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String textKey)) {
                    throw new IllegalStateException(
                            "stored chunker provider configuration contains a non-text key"
                    );
                }
                sorted.put(textKey, canonicalJsonValue(nested));
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(PostgresSpaceDocumentProcessingConfigStore::canonicalJsonValue)
                    .toList();
        }
        return value;
    }

    private static MapSqlParameterSource spaceParameters(
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
