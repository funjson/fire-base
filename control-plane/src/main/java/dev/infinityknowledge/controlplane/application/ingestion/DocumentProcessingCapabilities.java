package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
        .SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向空间配置用例暴露当前部署可发现的 Parser 和 Chunker 能力。
 *
 * <p>页面必须根据快照区分“已安装可执行”和“可安装但未启用”，不能假设所有环境
 * 都启用了语义模型或安装了相同的富文档 Adapter。</p>
 */
public interface DocumentProcessingCapabilities {

    /** 返回当前部署的稳定能力快照和默认配置。 */
    Snapshot snapshot();

    /**
     * 校验空间选择能够由当前部署实际执行。
     *
     * @throws IllegalArgumentException 显式 Parser 映射不兼容或 Provider 不可用
     */
    void validate(
            Map<String, String> parserSelections,
            ChunkerConfiguration chunker
    );

    /**
     * 校验新 Space 的完整创建快照。
     *
     * <p>除逐项可执行性外，新建时必须覆盖当前目录的全部规范格式；运行期则只校验
     * 已固化条目，避免部署后来新增格式使旧 Space 整体失效。</p>
     */
    void validateForCreation(
            Map<String, String> parserSelections,
            ChunkerConfiguration chunker
    );

    /**
     * 校验配置并返回当前部署真正会执行的完整实现合同。
     *
     * <p>调用方不得用能力目录中的公开版本字段自行拼接近似合同。</p>
     */
    DocumentProcessingContract processingContract(
            SpaceDocumentProcessingConfig config
    );

    /**
     * Parser 的可展示部署能力。
     *
     * <p>{@code outputCapabilities} 是 Adapter 对每次成功解析都保证提供的结构能力，
     * 控制台据此解释选项，后续 Chunk Adapter 也只能选择其要求能够被满足的组合。
     * {@code defaultSelection} 仅表示当前部署对该规范格式的默认选择，不会覆盖
     * 空间已持久化的显式选择。不可用能力只用于说明可安装选项，不能保存或执行。</p>
     */
    record ParserCapability(
            String id,
            String version,
            String canonicalMediaType,
            List<String> supportedMediaTypes,
            List<String> supportedExtensions,
            List<String> outputCapabilities,
            boolean defaultSelection,
            boolean available,
            String unavailableReason
    ) {

        /** 兼容只描述已安装 Parser 的调用点。 */
        public ParserCapability(
                String id,
                String version,
                String canonicalMediaType,
                List<String> supportedMediaTypes,
                List<String> supportedExtensions,
                List<String> outputCapabilities,
                boolean defaultSelection
        ) {
            this(
                    id,
                    version,
                    canonicalMediaType,
                    supportedMediaTypes,
                    supportedExtensions,
                    outputCapabilities,
                    defaultSelection,
                    true,
                    ""
            );
        }

        public ParserCapability {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(
                    canonicalMediaType,
                    "canonicalMediaType must not be null"
            );
            supportedMediaTypes = List.copyOf(supportedMediaTypes);
            supportedExtensions = List.copyOf(supportedExtensions);
            outputCapabilities = List.copyOf(outputCapabilities);
            unavailableReason = unavailableReason == null ? "" : unavailableReason;
            if (available && !unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "available parser must not have an unavailableReason"
                );
            }
            if (!available && unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable parser must declare an unavailableReason"
                );
            }
        }

    }

    /** Chunker Provider 的稳定身份、Parser 前置条件和部署可用状态。 */
    record ChunkerCapability(
            String id,
            String version,
            boolean available,
            String unavailableReason,
            List<String> requiredParserCapabilities,
            String defaultProviderConfigurationJson
    ) {

        public ChunkerCapability {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(version, "version must not be null");
            unavailableReason = unavailableReason == null ? "" : unavailableReason;
            requiredParserCapabilities = List.copyOf(requiredParserCapabilities);
            Objects.requireNonNull(
                    defaultProviderConfigurationJson,
                    "defaultProviderConfigurationJson must not be null"
            );
            if (available && !unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "available chunker must not have an unavailableReason"
                );
            }
        }
    }

    /**
     * Token Counter 的稳定身份和计量精度说明。
     * {@code modelProfileId} 只在运维把该 Tokenizer 与特定模型契约固定绑定时提供；
     * 该字段不表示服务端已经执行在线一致性验证。
     */
    record TokenizerCapability(
            String id,
            String version,
            String description,
            boolean exactModelTokens,
            String modelProfileId,
            boolean available,
            String unavailableReason
    ) {

        public TokenizerCapability {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(description, "description must not be null");
            modelProfileId = modelProfileId == null ? "" : modelProfileId;
            unavailableReason = unavailableReason == null ? "" : unavailableReason;
            if (available && !unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "available Tokenizer must not have an unavailableReason"
                );
            }
            if (!available && unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable Tokenizer must declare an unavailableReason"
                );
            }
        }
    }

    /** 允许语义细化使用的部署级 Embedding 契约；不包含密钥或 Endpoint。 */
    record EmbeddingProfileCapability(String id, String description) {

        public EmbeddingProfileCapability {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(description, "description must not be null");
        }
    }

    /** 当前能力目录，以及创建 Space 表单使用的实际可用默认配置。 */
    record Snapshot(
            List<ParserCapability> parsers,
            List<ChunkerCapability> chunkers,
            List<TokenizerCapability> tokenizers,
            List<EmbeddingProfileCapability> embeddingProfiles,
            Map<String, String> defaultParserSelections,
            ChunkerConfiguration defaultChunker
    ) {

        public Snapshot {
            parsers = List.copyOf(parsers);
            chunkers = List.copyOf(chunkers);
            tokenizers = List.copyOf(tokenizers);
            embeddingProfiles = List.copyOf(embeddingProfiles);
            defaultParserSelections = Map.copyOf(defaultParserSelections);
            Objects.requireNonNull(defaultChunker, "defaultChunker must not be null");
        }
    }
}
