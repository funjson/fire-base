package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 持久化知识空间的 Parser、内容清洗与 Chunker 配置。
 *
 * <p>配置只能随 Space 创建一次，后续只读。请求态版本为 0，持久化及执行态固定为 1；
 * Parser、Cleaner 与 Chunker 的有效配置指纹描述真正的处理身份。</p>
 */
public interface SpaceDocumentProcessingConfigStore {

    /** 查询一个空间已经持久化的文档处理配置。 */
    Optional<SpaceDocumentProcessingConfig> find(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    );

    /** 判断目标租户中是否存在活动空间。 */
    boolean activeSpaceExists(TenantId tenantId, KnowledgeSpaceId spaceId);

    /**
     * 为刚创建的 Space 写入一次性的不可变处理配置。
     *
     * <p>该操作必须参与调用方的 Space 创建事务；已存在时不得覆盖。所有配置调整
     * 都通过创建新 Space 完成，因此此端口不提供创建后的更新能力。</p>
     */
    CreateOutcome createImmutable(SpaceDocumentProcessingConfig config);

    /** 不可变配置的首次写入结果。 */
    enum CreateOutcome {
        CREATED,
        ALREADY_EXISTS
    }

    /** Parser 已明确识别的文档角色在清洗后的去向。 */
    enum CleaningAction {
        /** 保留为可索引正文。 */
        KEEP,
        /** 从知识处理结果中排除。 */
        REMOVE,
        /** 不参与检索，但保留为治理元数据。 */
        METADATA_ONLY
    }

    /**
     * 定义一个空间对常见文档家具和前置元数据的处理方式。
     *
     * <p>隐藏内容属于服务端安全边界，始终排除，因此不作为可配置字段暴露。</p>
     */
    record CleaningConfiguration(
            CleaningAction header,
            CleaningAction footer,
            CleaningAction pageNumber,
            CleaningAction watermark,
            CleaningAction frontMatter
    ) {

        /** 拒绝缺失的内容治理选择。 */
        public CleaningConfiguration {
            Objects.requireNonNull(header, "header must not be null");
            Objects.requireNonNull(footer, "footer must not be null");
            Objects.requireNonNull(pageNumber, "pageNumber must not be null");
            Objects.requireNonNull(watermark, "watermark must not be null");
            Objects.requireNonNull(frontMatter, "frontMatter must not be null");
        }

        /**
         * 返回与历史摄取结果兼容的默认策略。
         *
         * <p>既有 Markdown Parser 会忽略 Front Matter，因此统一 Parser 开始显式
         * 输出该角色后，默认仍应排除它，避免升级无意把配置元数据放入检索正文。</p>
         */
        public static CleaningConfiguration defaults() {
            return new CleaningConfiguration(
                    CleaningAction.KEEP,
                    CleaningAction.KEEP,
                    CleaningAction.KEEP,
                    CleaningAction.KEEP,
                    CleaningAction.REMOVE
            );
        }

    }

    /**
     * 描述一个空间选择的 Chunker Provider、统一 Token 尺寸和 Provider 专属配置。
     *
     * <p>Token 数必须由 {@code tokenizerId} 指向的已安装 TokenCounter 计算。当前内置
     * Counter 使用 UTF-8 字节数作为稳定预算估算（{@code exactModelTokens=false}），
     * 不等价于模型精确 Token 数，也不保证构成任意 Tokenizer 的数学硬上界。Provider
     * 专属 JSON 必须由 Provider 的上层强类型配置完成校验和 canonical 序列化；SPI
     * 只保存这个不可变结果，不解释第三方字段。</p>
     *
     * @param providerId 稳定 Chunker Provider 标识
     * @param tokenizerId 计算尺寸并参与处理契约的稳定 TokenCounter 标识
     * @param minimumTokens 普通 Chunk 的软最小 Token 数；硬结构边界可以产生更小块
     * @param targetTokens 普通 Chunk 的目标 Token 数
     * @param maximumTokens 单个 Chunk 不得突破的 Token 硬上限
     * @param overlapTokens 相邻 Chunk 的重叠 Token 数；不得跨越硬结构边界
     * @param providerConfigurationJson Provider 专属的 canonical JSON Object
     */
    record ChunkerConfiguration(
            String providerId,
            String tokenizerId,
            int minimumTokens,
            int targetTokens,
            int maximumTokens,
            int overlapTokens,
            String providerConfigurationJson
    ) {

        /** 约束所有 Provider 共用的稳定标识和 Token 尺寸不变量。 */
        public ChunkerConfiguration {
            providerId = requiredProviderId(providerId);
            tokenizerId = requiredTokenizerId(tokenizerId);
            if (minimumTokens < 1 || minimumTokens > 65_536) {
                throw new IllegalArgumentException(
                        "minimumTokens must be between 1 and 65536"
                );
            }
            if (targetTokens < minimumTokens || targetTokens > 65_536) {
                throw new IllegalArgumentException(
                        "targetTokens must be between minimumTokens and 65536"
                );
            }
            if (maximumTokens < targetTokens || maximumTokens > 65_536) {
                throw new IllegalArgumentException(
                        "maximumTokens must be between targetTokens and 65536"
                );
            }
            if (overlapTokens < 0 || overlapTokens >= minimumTokens) {
                throw new IllegalArgumentException(
                        "overlapTokens must be non-negative and less than minimumTokens"
                );
            }
            providerConfigurationJson = required(
                    providerConfigurationJson,
                    "providerConfigurationJson",
                    65_536
            );
        }

    }

    /**
     * 一套完整文档处理配置，可表示创建请求、TEST_ONLY 临时覆盖或 Space 的固化快照。
     *
     * @param tenantId 租户标识
     * @param spaceId 空间标识
     * @param parserSelections 规范媒体类型到稳定 Parser 标识的映射
     * @param cleaning 文档内容治理配置
     * @param chunker Chunker 策略和参数
     * @param processingContract 创建时固化的实际实现合同；请求映射阶段可以为空
     * @param version 创建请求和临时覆盖固定为 0；Space 固化及执行快照固定为 1
     * @param updatedBy 提交请求或创建固化快照的主体；执行快照恢复时可以为空
     * @param updatedAt 固化时间；尚未持久化或从执行快照恢复时使用纪元时间
     */
    record SpaceDocumentProcessingConfig(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            Map<String, String> parserSelections,
            CleaningConfiguration cleaning,
            ChunkerConfiguration chunker,
            DocumentProcessingContract processingContract,
            long version,
            PrincipalId updatedBy,
            Instant updatedAt
    ) {

        /** 保存稳定有序的 Parser 映射，便于处理指纹保持确定性。 */
        public SpaceDocumentProcessingConfig {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            parserSelections = normalizedSelections(parserSelections);
            Objects.requireNonNull(cleaning, "cleaning must not be null");
            Objects.requireNonNull(chunker, "chunker must not be null");
            if (version != 0L && version != 1L) {
                throw new IllegalArgumentException(
                        "version must be 0 before creation or 1 after creation"
                );
            }
            if (version == 1L && processingContract == null) {
                throw new IllegalArgumentException(
                        "persisted processing config must contain its implementation contract"
                );
            }
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }

        /**
         * 创建尚未计算实际实现合同的请求态配置。
         *
         * <p>仅允许版本 0；Space 固化和执行恢复必须使用包含合同的完整构造。</p>
         */
        public SpaceDocumentProcessingConfig(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                Map<String, String> parserSelections,
                CleaningConfiguration cleaning,
                ChunkerConfiguration chunker,
                long version,
                PrincipalId updatedBy,
                Instant updatedAt
        ) {
            this(
                    tenantId,
                    spaceId,
                    parserSelections,
                    cleaning,
                    chunker,
                    null,
                    version,
                    updatedBy,
                    updatedAt
            );
        }

        /**
         * 兼容不关心审计主体的内部构造；持久化 Adapter 读取数据库时必须使用
         * 包含 {@code updatedBy} 的完整构造。
         */
        public SpaceDocumentProcessingConfig(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                Map<String, String> parserSelections,
                CleaningConfiguration cleaning,
                ChunkerConfiguration chunker,
                long version,
                Instant updatedAt
        ) {
            this(
                    tenantId,
                    spaceId,
                    parserSelections,
                    cleaning,
                    chunker,
                    null,
                    version,
                    null,
                    updatedAt
            );
        }

        /** 返回绑定当前部署真实实现合同的新请求快照，不修改用户配置。 */
        public SpaceDocumentProcessingConfig withProcessingContract(
                DocumentProcessingContract contract
        ) {
            return new SpaceDocumentProcessingConfig(
                    tenantId,
                    spaceId,
                    parserSelections,
                    cleaning,
                    chunker,
                    Objects.requireNonNull(contract, "contract must not be null"),
                    version,
                    updatedBy,
                    updatedAt
            );
        }

        /** 比较会影响解析、清洗和 Chunk 结果的配置语义，忽略审计字段。 */
        public boolean hasSameProcessingConfiguration(
                SpaceDocumentProcessingConfig other
        ) {
            return other != null
                    && parserSelections.equals(other.parserSelections)
                    && cleaning.equals(other.cleaning)
                    && chunker.equals(other.chunker);
        }

    }

    private static Map<String, String> normalizedSelections(Map<String, String> values) {
        Objects.requireNonNull(values, "parserSelections must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("parserSelections must not be empty");
        }
        Map<String, String> normalized = new TreeMap<>();
        values.forEach((mediaType, parserId) -> {
            String normalizedMediaType = required(mediaType, "mediaType", 128)
                    .toLowerCase(java.util.Locale.ROOT);
            String normalizedParserId = required(parserId, "parserId", 128);
            if (normalized.put(normalizedMediaType, normalizedParserId) != null) {
                throw new IllegalArgumentException(
                        "parserSelections contains a duplicate mediaType"
                );
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    private static String required(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }

    /** 限制 Provider 标识字符集，避免把外部类名或配置正文写入选择字段。 */
    private static String requiredProviderId(String value) {
        String normalized = required(value, "providerId", 64);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(
                    "providerId must match [A-Z][A-Z0-9_]{0,63}"
            );
        }
        return normalized;
    }

    /** TokenCounter 标识允许使用常见的分层名称分隔符，但不接受空白或类名正文。 */
    private static String requiredTokenizerId(String value) {
        String normalized = required(value, "tokenizerId", 128);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "tokenizerId must contain only letters, digits, '.', '_', ':' or '-'"
            );
        }
        return normalized;
    }
}
