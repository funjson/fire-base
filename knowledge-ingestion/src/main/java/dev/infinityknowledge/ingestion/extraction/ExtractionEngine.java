package dev.infinityknowledge.ingestion.extraction;

import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningResult;
import dev.infinityknowledge.ingestion.cleaning.ElementCleaningDecision;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
        .SpaceDocumentProcessingConfig;

import java.time.Duration;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 唯一的无存储副作用文档抽取内核，按 Parse -> Clean -> Chunk 顺序执行。
 *
 * <p>生产摄取、配置试验和重处理应调用同一个引擎。该类型不解析空间配置、不访问
 * 数据库或对象存储，也不决定发布状态；调用方只需提供一次不可变配置快照。</p>
 */
public final class ExtractionEngine {
    /** 已经参与历史修订身份，修改时必须同步制定数据迁移方案。 */
    public static final String PIPELINE_CONTRACT =
            DocumentProcessingContractFactory.PIPELINE_CONTRACT;
    private static final String NO_INDEXABLE_ELEMENTS =
            "document did not contain indexable text after cleaning";

    private final DocumentParserRegistry parsers;
    private final KnowledgeChunkerFactory chunkers;
    private final DocumentCleaningPolicy cleaner;
    private final DocumentProcessingContractFactory contractFactory;
    private final LongSupplier ticker;

    /** 创建只依赖领域 Parser、Cleaner 和 Chunker 契约的抽取引擎。 */
    public ExtractionEngine(
            DocumentParserRegistry parsers,
            KnowledgeChunkerFactory chunkers,
            DocumentCleaningPolicy cleaner
    ) {
        this(
                parsers,
                chunkers,
                cleaner,
                new DocumentProcessingContractFactory(parsers, chunkers, cleaner),
                System::nanoTime
        );
    }

    /** 创建显式复用系统处理合同工厂的生产抽取内核。 */
    public ExtractionEngine(
            DocumentParserRegistry parsers,
            KnowledgeChunkerFactory chunkers,
            DocumentCleaningPolicy cleaner,
            DocumentProcessingContractFactory contractFactory
    ) {
        this(parsers, chunkers, cleaner, contractFactory, System::nanoTime);
    }

    /**
     * 创建使用可控单调时钟的抽取引擎，供评测和确定性测试采集阶段耗时。
     *
     * <p>{@code ticker} 返回纳秒刻度，只用于计算相对耗时，不进入修订身份。</p>
     */
    public ExtractionEngine(
            DocumentParserRegistry parsers,
            KnowledgeChunkerFactory chunkers,
            DocumentCleaningPolicy cleaner,
            LongSupplier ticker
    ) {
        this(
                parsers,
                chunkers,
                cleaner,
                new DocumentProcessingContractFactory(parsers, chunkers, cleaner),
                ticker
        );
    }

    /** 创建同时使用可控时钟和显式合同工厂的抽取内核。 */
    public ExtractionEngine(
            DocumentParserRegistry parsers,
            KnowledgeChunkerFactory chunkers,
            DocumentCleaningPolicy cleaner,
            DocumentProcessingContractFactory contractFactory,
            LongSupplier ticker
    ) {
        this.parsers = Objects.requireNonNull(parsers, "parsers must not be null");
        this.chunkers = Objects.requireNonNull(chunkers, "chunkers must not be null");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner must not be null");
        this.contractFactory = Objects.requireNonNull(
                contractFactory,
                "contractFactory must not be null"
        );
        this.ticker = Objects.requireNonNull(ticker, "ticker must not be null");
    }

    /** 返回配置在当前内核实际会执行的完整处理合同，不读取来源或调用模型。 */
    public DocumentProcessingContract processingContract(
            SpaceDocumentProcessingConfig config
    ) {
        return contractFactory.create(config);
    }

    /**
     * 执行一次确定的 Parse、Clean 和 Chunk，并返回可供不同写入模式复用的结果。
     *
     * @param request 已有界读取且携带单次配置快照的抽取请求
     * @return 不包含任何存储副作用的结构化抽取结果
     */
    public ExtractionResult extract(ExtractionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        verifyContentHash(request);

        var config = request.processingConfig();
        DocumentProcessingContract currentContract = processingContract(config);
        if (!currentContract.equals(config.processingContract())
                || !DocumentProcessingContractFactory.supportsSourceNormalizer(
                        request.normalizerContract()
                )) {
            throw new DocumentProcessingContractMismatchException();
        }
        var parserSelection = parsers.select(
                request.mediaType(),
                request.fileName(),
                config.parserSelections()
        );
        var chunker = chunkers.create(config.chunker());
        var cleaningConfiguration = DocumentCleaningConfiguration.from(config.cleaning());
        String parserContract = parserSelection.contract();
        String cleanerContract = cleaner.contract(cleaningConfiguration);
        String chunkerContract = chunker.contract();
        String processorVersion = processorVersion(
                request.normalizerContract(),
                cleanerContract,
                parserContract,
                chunkerContract
        );
        var revisionId = IngestionIdentity.revisionId(
                request.documentId(),
                request.contentHash(),
                parserSelection.format().mediaType(),
                request.language(),
                processorVersion
        );

        long parseStartedAt = ticker.getAsLong();
        var parsed = parserSelection.parse(
                revisionId,
                request.fileName(),
                request.sourceBytes(),
                request.parseLimits()
        );
        Duration parseDuration = elapsedSince(parseStartedAt);

        long cleaningStartedAt = ticker.getAsLong();
        DocumentCleaningResult cleaned = cleaner.clean(parsed, cleaningConfiguration);
        Duration cleaningDuration = elapsedSince(cleaningStartedAt);
        if (!cleanerContract.equals(cleaned.contract())) {
            throw new IllegalStateException(
                    "cleaner result contract differs from the selected cleaning contract"
            );
        }
        validateCleaningPartition(parsed.elements(), cleaned);
        if (cleaned.indexableElements().isEmpty()) {
            throw new DocumentParseException(NO_INDEXABLE_ELEMENTS);
        }

        long chunkingStartedAt = ticker.getAsLong();
        var chunking = chunker.chunk(
                request.tenantId(),
                request.spaceId(),
                request.documentId(),
                revisionId,
                cleaned.indexableElements()
        );
        Duration chunkingDuration = elapsedSince(chunkingStartedAt);
        if (chunking.chunks().isEmpty()) {
            throw new DocumentParseException("document did not produce indexable chunks");
        }

        var contracts = new ExtractionContracts(
                PIPELINE_CONTRACT,
                request.normalizerContract(),
                parserContract,
                cleanerContract,
                chunkerContract,
                processorVersion
        );
        var diagnostics = new ExtractionDiagnostics(
                new ExtractionDiagnostics.ParseStage(
                        parsed.parserId(),
                        parserSelection.format().mediaType(),
                        parsed.elements().size(),
                        parseDuration
                ),
                new ExtractionDiagnostics.CleaningStage(
                        cleaned.indexableElements().size(),
                        cleaned.metadataOnlyElements().size(),
                        cleaned.reasonCodeCounts(),
                        cleaningDuration
                ),
                new ExtractionDiagnostics.ChunkStage(
                        chunking.diagnostics(),
                        chunkingDuration
                )
        );
        return new ExtractionResult(
                revisionId,
                request.contentHash(),
                parserSelection.format().mediaType(),
                parsed.artifact(),
                parsed.provenance(),
                cleaned.decisions(),
                retainedElements(cleaned),
                chunking.chunks(),
                contracts,
                diagnostics
        );
    }

    /**
     * Cleaner 必须对每个 Parser Element 给出且只给出一个去向，禁止把删除伪装成遗漏。
     */
    private static void validateCleaningPartition(
            List<KnowledgeElement> parsedElements,
            DocumentCleaningResult cleaned
    ) {
        Set<UUID> parsedIds = parsedElements.stream()
                .map(KnowledgeElement::id)
                .collect(Collectors.toCollection(HashSet::new));
        if (parsedIds.size() != parsedElements.size()) {
            throw new IllegalStateException("parser output contains duplicate element ids");
        }
        Map<UUID, ElementCleaningDecision> decisions =
                cleaned.decisions().stream().collect(Collectors.toMap(
                        ElementCleaningDecision::elementId,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException(
                                    "cleaner returned duplicate element decisions"
                            );
                        }
                ));
        if (!decisions.keySet().equals(parsedIds)) {
            throw new IllegalStateException(
                    "cleaner must report one decision for every parsed element"
            );
        }
        Set<UUID> indexableIds = ids(cleaned.indexableElements());
        Set<UUID> metadataOnlyIds = ids(cleaned.metadataOnlyElements());
        if (!Collections.disjoint(indexableIds, metadataOnlyIds)) {
            throw new IllegalStateException("cleaner returned an element in two destinations");
        }
        decisions.forEach((elementId, decision) -> {
            boolean inIndexable = indexableIds.contains(elementId);
            boolean inMetadata = metadataOnlyIds.contains(elementId);
            boolean valid = switch (decision.action()) {
                case KEEP -> inIndexable && !inMetadata;
                case METADATA_ONLY -> !inIndexable && inMetadata;
                case REMOVE -> !inIndexable && !inMetadata;
            };
            if (!valid) {
                throw new IllegalStateException(
                        "cleaner decision differs from its returned element destination"
                );
            }
        });
    }

    private static Set<UUID> ids(List<KnowledgeElement> elements) {
        Set<UUID> values = elements.stream()
                .map(KnowledgeElement::id)
                .collect(Collectors.toCollection(HashSet::new));
        if (values.size() != elements.size()) {
            throw new IllegalStateException("cleaner returned duplicate elements");
        }
        return values;
    }

    /** 将单调纳秒刻度转换为非负 Duration，避免墙上时钟调整污染阶段指标。 */
    private Duration elapsedSince(long startedAt) {
        long elapsedNanos = ticker.getAsLong() - startedAt;
        return Duration.ofNanos(Math.max(0L, elapsedNanos));
    }

    /** 内容指纹只在抽取边界校验一次，后续各阶段共享同一份可信输入。 */
    private static void verifyContentHash(ExtractionRequest request) {
        String actualHash = IngestionIdentity.sha256(request.sourceBytes());
        if (!actualHash.equals(request.contentHash())) {
            throw new IllegalArgumentException(
                    "contentHash does not match the supplied source bytes"
            );
        }
    }

    /** 按历史字段顺序生成 64 字符以内的修订处理指纹，避免重构导致身份漂移。 */
    private static String processorVersion(
            String normalizerContract,
            String cleanerContract,
            String parserContract,
            String chunkerContract
    ) {
        String fingerprint = IngestionIdentity.sha256(
                normalizerContract + "\u001f"
                        + cleanerContract + "\u001f"
                        + parserContract + "\u001f"
                        + chunkerContract
        );
        return PIPELINE_CONTRACT + ":" + fingerprint.substring(0, 48);
    }

    /** 合并治理保留元素并恢复 Parser 的全局阅读顺序。 */
    private static List<KnowledgeElement> retainedElements(DocumentCleaningResult cleaned) {
        return Stream.concat(
                        cleaned.indexableElements().stream(),
                        cleaned.metadataOnlyElements().stream()
                )
                .sorted(Comparator.comparingInt(KnowledgeElement::ordinal))
                .toList();
    }
}
