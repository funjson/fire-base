package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.Case;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceReport.Finding;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Chunk;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.ingestion.chunking.Utf8ByteBudgetTokenCounter;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.extraction.ExtractionRequest;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证真实 ExtractionEngine 可离线执行，并能拦截五项确定性抽取门禁。 */
class ExtractionGoldenDatasetTest {
    private static final String DATASET_RESOURCE = "extraction-acceptance/dataset.json";
    private static final TenantId TENANT_ID = new TenantId("golden-tenant");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("golden-space");
    private static final TokenCounter TOKEN_COUNTER = new Utf8ByteBudgetTokenCounter();
    private static final ChunkerConfiguration CHUNKER = new ChunkerConfiguration(
            KnowledgeChunkerFactory.STRUCTURAL,
            TOKEN_COUNTER.id(),
            64,
            256,
            512,
            32,
            "{}"
    );
    private static final ChunkerConfiguration WIDE_CHUNKER = new ChunkerConfiguration(
            KnowledgeChunkerFactory.STRUCTURAL,
            TOKEN_COUNTER.id(),
            128,
            512,
            1024,
            32,
            "{}"
    );

    private final ExtractionDatasetLoader loader = new ExtractionDatasetLoader();
    private final ExtractionAcceptanceRunner runner = new ExtractionAcceptanceRunner();
    private final ExtractionObservationFactory observations = new ExtractionObservationFactory();

    @Test
    void real_markdown_and_plain_text_pipeline_should_pass_golden_dataset() throws Exception {
        ExtractionAcceptanceDataset dataset = loadDataset();
        ExtractionAcceptanceReport report = runner.run(dataset, extract(dataset));

        assertThat(report.passed())
                .withFailMessage("真实抽取验收未通过：%s", report.cases())
                .isTrue();
        assertThat(report.cases()).hasSize(2).allSatisfy(result -> {
            assertThat(result.processingContracts().processorVersion())
                    .startsWith("pipeline-v7:");
            assertThat(result.processingContracts().tokenizerContract())
                    .isEqualTo(TOKEN_COUNTER.contract());
            assertThat(result.parseSuccessRate()).isEqualTo(1.0D);
            assertThat(result.tokenOverflowCount()).isZero();
            assertThat(result.invalidSourceSpanCount()).isZero();
            assertThat(result.sourceAccountingRate()).isEqualTo(1.0D);
            assertThat(result.silentTruncationCount()).isZero();
            assertThat(result.findings()).isEmpty();
        });
    }

    @Test
    void boundary_labels_should_remain_valid_for_a_larger_legal_token_budget() throws Exception {
        ExtractionAcceptanceDataset dataset = loadDataset();
        SpaceDocumentProcessingConfig config = config(
                WIDE_CHUNKER,
                CleaningAction.METADATA_ONLY
        );
        List<ExtractionObservation> actual = dataset.cases().stream()
                .map(goldenCase -> observations.create(
                        goldenCase.id(),
                        extractResult(goldenCase, config),
                        TOKEN_COUNTER,
                        config.chunker()
                ))
                .toList();

        assertThat(runner.run(dataset, actual).passed())
                .as("Golden 边界应是跨合法 Token 预算都成立的结构不变量")
                .isTrue();
    }

    @Test
    void candidate_should_report_every_hard_gate_failure() throws Exception {
        ExtractionAcceptanceDataset dataset = loadDataset();
        ExtractionObservation baseline = extract(dataset).getFirst();
        List<Chunk> brokenChunks = new ArrayList<>(baseline.chunks());
        Chunk first = brokenChunks.getFirst();
        brokenChunks.set(0, new Chunk(
                first.ordinal(),
                List.of(new SourceRange(0, 10_000)),
                first.tokenLimit() + 1,
                first.tokenLimit()
        ));
        brokenChunks.removeLast();
        ExtractionObservation candidate = copy(
                baseline,
                false,
                baseline.elements().subList(0, baseline.elements().size() - 1),
                brokenChunks
        );

        ExtractionAcceptanceReport report = runner.run(
                new ExtractionAcceptanceDataset(
                        dataset.datasetVersion(),
                        List.of(dataset.cases().getFirst())
                ),
                List.of(candidate)
        );
        Set<String> codes = report.cases().getFirst().findings().stream()
                .map(Finding::code)
                .collect(Collectors.toSet());

        assertThat(report.passed()).isFalse();
        assertThat(codes).contains(
                "PARSE_SUCCESS_GATE_FAILED",
                "TOKEN_OVERFLOW_GATE_FAILED",
                "SOURCE_SPAN_GATE_FAILED",
                "SOURCE_ACCOUNTING_GATE_FAILED",
                "SILENT_TRUNCATION_GATE_FAILED"
        );
    }

    @Test
    void removing_table_chunk_must_be_reported_as_silent_truncation() throws Exception {
        ExtractionAcceptanceDataset dataset = loadDataset();
        ExtractionObservation baseline = extract(dataset).getFirst();
        ExtractionObservation candidate = copy(
                baseline,
                baseline.parseSucceeded(),
                baseline.elements(),
                baseline.chunks().stream()
                        .filter(chunk -> chunk.sourceSpans().stream().noneMatch(range ->
                                range.overlaps(new SourceRange(216, 279))))
                        .toList()
        );

        ExtractionAcceptanceReport report = runner.run(
                new ExtractionAcceptanceDataset(
                        dataset.datasetVersion(),
                        List.of(dataset.cases().getFirst())
                ),
                List.of(candidate)
        );

        assertThat(report.cases().getFirst().findings())
                .extracting(Finding::code)
                .contains(
                        "PRESERVE_RANGE_MISSING",
                        "SILENT_TRUNCATION_GATE_FAILED"
                );
    }

    @Test
    void tampered_source_should_be_rejected_before_labels_are_loaded(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path manifest = temporaryDirectory.resolve("dataset.json");
        Path sourceDirectory = Files.createDirectories(temporaryDirectory.resolve("sources"));
        Files.copy(resource("dataset.json"), manifest);
        Files.copy(
                resource("sources/plain-text-enterprise.txt"),
                sourceDirectory.resolve("plain-text-enterprise.txt")
        );
        byte[] tamperedSource = Files.readAllBytes(
                resource("sources/markdown-enterprise.md")
        );
        tamperedSource[0] ^= 1;
        Files.write(sourceDirectory.resolve("markdown-enterprise.md"), tamperedSource);

        assertThatThrownBy(() -> loader.loadDataset(manifest))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void classpath_loader_should_expose_runtime_dataset() throws Exception {
        ExtractionAcceptanceDataset dataset = loader.loadDataset(
                DATASET_RESOURCE,
                ExtractionGoldenDatasetTest.class.getClassLoader()
        );

        assertThat(dataset.cases())
                .extracting(Case::id)
                .containsExactly(
                        "markdown-enterprise-structures",
                        "plain-text-paragraphs"
                );
    }

    @Test
    void observation_factory_must_recount_each_chunk_with_actual_counter() throws Exception {
        Case goldenCase = loadDataset().cases().getFirst();
        ExtractionResult result = extractResult(goldenCase);
        CountingTokenCounter counter = new CountingTokenCounter();

        ExtractionObservation observation = observations.create(
                goldenCase.id(),
                result,
                counter,
                CHUNKER
        );

        assertThat(counter.calls()).isEqualTo(result.chunks().size());
        assertThat(observation.chunks()).extracting(Chunk::tokenCount)
                .containsExactlyElementsOf(result.chunks().stream()
                        .map(chunk -> TOKEN_COUNTER.count(chunk.contextualText()))
                        .toList());
        assertThat(observation.processingContracts().tokenizerContract())
                .isEqualTo(counter.contract());
    }

    @Test
    void removed_element_must_keep_artifact_range_without_body_copy() throws Exception {
        ExtractionAcceptanceDataset dataset = loadDataset();
        Case goldenCase = dataset.cases().getFirst();
        SpaceDocumentProcessingConfig config = config(CleaningAction.REMOVE);
        ExtractionResult result = extractResult(goldenCase, config);

        ExtractionObservation observation = observations.create(
                goldenCase.id(),
                result,
                TOKEN_COUNTER,
                config.chunker()
        );

        assertThat(observation.removedRanges()).containsExactly(new SourceRange(4, 39));
        assertThat(observation.elements().getFirst().disposition())
                .isEqualTo(ExtractionObservation.Disposition.REMOVED);
        assertThat(observation.elements().getFirst().role()).isEqualTo("FRONT_MATTER");
        assertThat(result.retainedElements())
                .noneMatch(element -> "FRONT_MATTER".equals(element.attributes().get("role")));
        assertThat(result.cleaningDecisions().getFirst().reasonCode())
                .isEqualTo("FRONT_MATTER_REMOVED");
        assertThat(runner.run(
                new ExtractionAcceptanceDataset(
                        dataset.datasetVersion(),
                        List.of(goldenCase)
                ),
                List.of(observation)
        ).passed()).isTrue();
    }

    private List<ExtractionObservation> extract(ExtractionAcceptanceDataset dataset) {
        return dataset.cases().stream().map(goldenCase -> {
            SpaceDocumentProcessingConfig config = config();
            return observations.create(
                    goldenCase.id(),
                    extractResult(goldenCase),
                    TOKEN_COUNTER,
                    config.chunker()
            );
        }).toList();
    }

    private static ExtractionResult extractResult(Case goldenCase) {
        return extractResult(goldenCase, config());
    }

    private static ExtractionResult extractResult(
            Case goldenCase,
            SpaceDocumentProcessingConfig config
    ) {
        ExtractionRequest request = new ExtractionRequest(
                TENANT_ID,
                SPACE_ID,
                new DocumentId(UUID.nameUUIDFromBytes(
                        goldenCase.id().getBytes(StandardCharsets.UTF_8)
                )),
                goldenCase.mediaType(),
                goldenCase.sourceName(),
                "und",
                DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT,
                goldenCase.sourceBytes(),
                goldenCase.sourceSha256(),
                DocumentParseLimits.defaults(),
                config
        );
        return extractionEngine().extract(request);
    }

    private static SpaceDocumentProcessingConfig config() {
        return config(CleaningAction.METADATA_ONLY);
    }

    private static SpaceDocumentProcessingConfig config(CleaningAction frontMatter) {
        return config(CHUNKER, frontMatter);
    }

    private static SpaceDocumentProcessingConfig config(
            ChunkerConfiguration chunker,
            CleaningAction frontMatter
    ) {
        SpaceDocumentProcessingConfig unresolved = new SpaceDocumentProcessingConfig(
                TENANT_ID,
                SPACE_ID,
                DocumentParserRegistry.standard().defaultParserSelections(),
                new CleaningConfiguration(
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        frontMatter
                ),
                chunker,
                0L,
                Instant.EPOCH
        );
        return unresolved.withProcessingContract(
                extractionEngine().processingContract(unresolved)
        );
    }

    /** Golden 测试与真实执行共用同一组件装配，防止实现合同与执行内核漂移。 */
    private static ExtractionEngine extractionEngine() {
        return new ExtractionEngine(
                DocumentParserRegistry.standard(),
                new KnowledgeChunkerFactory(List.of(), List.of()),
                new DeterministicDocumentCleaningPolicy()
        );
    }

    private ExtractionAcceptanceDataset loadDataset() throws Exception {
        return loader.loadDataset(resource("dataset.json"));
    }

    private static ExtractionObservation copy(
            ExtractionObservation baseline,
            boolean parseSucceeded,
            List<ExtractionObservation.Element> elements,
            List<Chunk> chunks
    ) {
        return new ExtractionObservation(
                baseline.caseId(),
                baseline.sourceSha256(),
                baseline.artifactSha256(),
                baseline.artifactContract(),
                baseline.artifactLength(),
                baseline.processingContracts(),
                parseSucceeded,
                elements,
                chunks,
                baseline.removedRanges(),
                baseline.reportedTruncationRanges()
        );
    }

    private static Path resource(String relative) throws URISyntaxException {
        return Path.of(ExtractionGoldenDatasetTest.class.getResource(
                "/extraction-acceptance/" + relative
        ).toURI());
    }

    /** 与生产内置 Counter 合同相同，只额外记录 Factory 是否真的执行了重算。 */
    private static final class CountingTokenCounter implements TokenCounter {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            return TOKEN_COUNTER.id();
        }

        @Override
        public String version() {
            return TOKEN_COUNTER.version();
        }

        @Override
        public String description() {
            return TOKEN_COUNTER.description();
        }

        @Override
        public boolean exactModelTokens() {
            return TOKEN_COUNTER.exactModelTokens();
        }

        @Override
        public String modelProfileId() {
            return TOKEN_COUNTER.modelProfileId();
        }

        @Override
        public int count(String text) {
            calls.incrementAndGet();
            return TOKEN_COUNTER.count(text);
        }

        @Override
        public int maximumPrefixEnd(
                String text,
                int startOffset,
                int endOffset,
                int maximumTokens
        ) {
            return TOKEN_COUNTER.maximumPrefixEnd(
                    text,
                    startOffset,
                    endOffset,
                    maximumTokens
            );
        }

        int calls() {
            return calls.get();
        }
    }
}
