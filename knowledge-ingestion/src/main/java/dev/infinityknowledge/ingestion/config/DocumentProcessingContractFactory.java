package dev.infinityknowledge.ingestion.config;

import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
        .SpaceDocumentProcessingConfig;

import java.util.Objects;

/**
 * 根据当前部署真实实现生成可固化、可复核的文档处理合同。
 *
 * <p>创建 Space、同步摄取、异步 Worker 和测试覆盖必须复用本工厂；只比较 Adapter
 * ID 或公开版本不足以发现同 ID 实现、Tokenizer 文件或模型预算发生漂移。</p>
 */
public final class DocumentProcessingContractFactory {

    /** Parse -> Clean -> Chunk 主线的实现合同。 */
    public static final String PIPELINE_CONTRACT = "pipeline-v7";

    /** 文件上传与二进制 Parser 接收原始字节时使用的来源合同。 */
    public static final String IDENTITY_NORMALIZER_CONTRACT = "identity-bytes-v1";

    /** 同步 Markdown 入口在进入 Parser 前统一换行时使用的来源合同。 */
    public static final String MARKDOWN_NORMALIZER_CONTRACT = "newline-lf-v1";

    /** 当前系统所有入口允许执行的来源规范化规则集合。 */
    public static final String NORMALIZER_SCHEMA_CONTRACT = String.join(
            ";",
            "normalizer-schema-v2",
            "markdown=" + MARKDOWN_NORMALIZER_CONTRACT,
            "binary=" + IDENTITY_NORMALIZER_CONTRACT
    );

    private final DocumentParserRegistry parsers;
    private final KnowledgeChunkerFactory chunkers;
    private final DocumentCleaningPolicy cleaner;

    /** 创建绑定当前部署 Parser、Cleaner、Chunker 与 Tokenizer 的合同工厂。 */
    public DocumentProcessingContractFactory(
            DocumentParserRegistry parsers,
            KnowledgeChunkerFactory chunkers,
            DocumentCleaningPolicy cleaner
    ) {
        this.parsers = Objects.requireNonNull(parsers, "parsers must not be null");
        this.chunkers = Objects.requireNonNull(chunkers, "chunkers must not be null");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner must not be null");
    }

    /** 计算配置在当前部署实际会执行的完整合同，不调用解析或外部模型。 */
    public DocumentProcessingContract create(
            SpaceDocumentProcessingConfig config
    ) {
        Objects.requireNonNull(config, "config must not be null");
        return DocumentProcessingContract.create(
                PIPELINE_CONTRACT,
                NORMALIZER_SCHEMA_CONTRACT,
                parsers.selectedParserContracts(config.parserSelections()),
                cleaner.contract(
                        DocumentCleaningConfiguration.from(config.cleaning())
                ),
                chunkers.contract(config.chunker())
        );
    }

    /** 防止入口传入未进入 Space 固化规范化规则集合的临时实现。 */
    public static boolean supportsSourceNormalizer(String contract) {
        return IDENTITY_NORMALIZER_CONTRACT.equals(contract)
                || MARKDOWN_NORMALIZER_CONTRACT.equals(contract);
    }
}
