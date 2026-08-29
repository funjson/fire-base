package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerProvider;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingBudget;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.parser.DocumentParser;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.ingestion.parser.DocxDocumentParser;
import dev.infinityknowledge.ingestion.parser.HtmlDocumentParser;
import dev.infinityknowledge.ingestion.parser.MarkdownDocumentParser;
import dev.infinityknowledge.ingestion.parser.PdfDocumentParser;
import dev.infinityknowledge.ingestion.parser.PlainTextDocumentParser;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 组装与来源类型无关的 Parser 和 Chunker 主线。
 *
 * <p>Markdown、文件上传和 Connector 共用这里的契约，避免每个入口维护一套
 * Parser/Chunker 版本与参数。</p>
 */
@Configuration
public class IngestionRuntimeConfiguration {

    /**
     * 隔离同步摄入的模型调用，避免文档上传并发无限放大外部模型请求。
     *
     * <p>队列只允许一个等待任务：调用方会受语义阶段总时限约束，满载时立即得到
     * 可重试的失败，而不是长期占用 Web 或 Connector 工作线程。</p>
     */
    @Bean(name = "semanticChunkingExecutor", destroyMethod = "shutdown")
    ExecutorService semanticChunkingExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().name("knowledge-semantic-chunking-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 汇总运行时安装的结构 Parser，并明确保留当前内置实现作为部署默认值。
     *
     * <p>新增 Docling 等同格式 Adapter 只会扩充页面选项，不会因为 Bean 顺序或
     * Parser ID 排序而静默改变尚未配置空间的处理语义。</p>
     *
     * @param parsers 已安装 Parser
     * @return 统一文档解析器注册表
     */
    @Bean
    DocumentParserRegistry documentParserRegistry(List<DocumentParser> parsers) {
        return new DocumentParserRegistry(parsers, Map.of(
                "text/markdown", "markdown-structure",
                "text/plain", "plain-text",
                "text/html", "html-structure",
                "application/pdf", "pdfbox-page",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "poi-docx-structure"
        ));
    }

    @Bean
    DocumentParser markdownDocumentParser() {
        return new MarkdownDocumentParser();
    }

    @Bean
    DocumentParser plainTextDocumentParser() {
        return new PlainTextDocumentParser();
    }

    @Bean
    DocumentParser htmlDocumentParser() {
        return new HtmlDocumentParser();
    }

    @Bean
    DocumentParser pdfDocumentParser() {
        return new PdfDocumentParser();
    }

    @Bean
    DocumentParser docxDocumentParser() {
        return new DocxDocumentParser();
    }

    /**
     * 创建 Parser 与 Chunker 之间的确定性内容治理策略。
     *
     * <p>策略只读取 Parser 明确标注的结构角色，不执行任意正则或模型调用。</p>
     */
    @Bean
    DocumentCleaningPolicy documentCleaningPolicy() {
        return new DeterministicDocumentCleaningPolicy();
    }

    /**
     * 组装生产摄取与抽取试验共用的唯一 Parse、Clean、Chunk 内核。
     *
     * <p>引擎本身不访问存储；不同用例只在它之后选择正式发布或试验结果保存。</p>
     */
    @Bean
    DocumentProcessingContractFactory documentProcessingContractFactory(
            DocumentParserRegistry parserRegistry,
            KnowledgeChunkerFactory chunkerFactory,
            DocumentCleaningPolicy cleaningPolicy
    ) {
        return new DocumentProcessingContractFactory(
                parserRegistry,
                chunkerFactory,
                cleaningPolicy
        );
    }

    /** 组装同时执行抽取并复核实际实现合同的唯一内核。 */
    @Bean
    ExtractionEngine extractionEngine(
            DocumentParserRegistry parserRegistry,
            KnowledgeChunkerFactory chunkerFactory,
            DocumentCleaningPolicy cleaningPolicy,
            DocumentProcessingContractFactory contractFactory
    ) {
        return new ExtractionEngine(
                parserRegistry,
                chunkerFactory,
                cleaningPolicy,
                contractFactory
        );
    }

    /**
     * 创建按空间文档处理配置选择 Chunker 的工厂。
     *
     * <p>空间只能选择已安装 Provider、Token Counter 和开放参数；语义模型、预算、
     * 超时及执行器都由部署固定。配置关闭或模型能力缺失时仍允许服务启动，但语义
     * Provider 不会进入可用能力列表；外部精确 Tokenizer Adapter 不受该开关影响。</p>
     */
    @Bean
    KnowledgeChunkerFactory knowledgeChunkerFactory(
            FileIngestionProperties fileProperties,
            SemanticChunkingProperties semanticProperties,
            ObjectProvider<EmbeddingProvider> embeddingProviders,
            ObjectProvider<EmbeddingSpec> embeddingSpecs,
            ObjectProvider<KnowledgeChunkerProvider> chunkerProviders,
            ObjectProvider<TokenCounter> tokenCounters,
            @Qualifier("semanticChunkingExecutor") ExecutorService semanticExecutor
    ) {
        List<KnowledgeChunkerProvider> externalProviders = chunkerProviders
                .orderedStream()
                .toList();
        List<TokenCounter> externalTokenCounters = tokenCounters
                .orderedStream()
                .toList();
        if (!semanticProperties.enabled()) {
            return new KnowledgeChunkerFactory(
                    externalProviders,
                    externalTokenCounters
            );
        }
        EmbeddingProvider provider = embeddingProviders.getIfAvailable();
        EmbeddingSpec spec = embeddingSpecs.getIfAvailable();
        if (provider == null || spec == null) {
            return new KnowledgeChunkerFactory(
                    externalProviders,
                    externalTokenCounters
            );
        }
        SemanticChunkingBudget budget = new SemanticChunkingBudget(
                fileProperties.maximumElements(),
                fileProperties.maximumTextCharacters(),
                semanticProperties.maximumEmbeddingInputs(),
                semanticProperties.maximumInputCharacters(),
                semanticProperties.maximumCandidates(),
                semanticProperties.maximumVectorValues(),
                semanticProperties.stageTimeout()
        );
        return new KnowledgeChunkerFactory(
                provider,
                spec,
                budget,
                semanticExecutor,
                externalProviders,
                externalTokenCounters
        );
    }

    /**
     * 让所有索引投影使用摄取执行期同一套空间文档处理配置和处理契约。
     */
    @Bean
    SpaceIndexingContractResolver spaceIndexingContractResolver(
            SpaceDocumentProcessingConfigResolver configResolver
    ) {
        return new DocumentProcessingConfigIndexingContractResolver(
                configResolver
        );
    }
}
