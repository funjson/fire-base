package dev.infinityknowledge.controlplane.application.ingestion.extraction;

import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceRunner;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservationFactory;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.runtime.extraction.ExtractionGateEvaluator;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 使用统一 ObservationFactory 与 AcceptanceRunner 执行 Space 抽取硬门禁。 */
@Component
public final class DatasetExtractionGateEvaluator implements ExtractionGateEvaluator {

    private final ExtractionDatasetCatalog catalog;
    private final KnowledgeChunkerFactory chunkers;
    private final Clock clock;

    /** 注入与真实 ExtractionEngine 相同的 TokenCounter 能力目录。 */
    public DatasetExtractionGateEvaluator(
            ExtractionDatasetCatalog catalog,
            KnowledgeChunkerFactory chunkers,
            Clock clock
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.chunkers = Objects.requireNonNull(chunkers, "chunkers must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Session start(RunSnapshot run, SpaceDocumentProcessingConfig config) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(config, "config must not be null");
        ExtractionAcceptanceDataset dataset = catalog.require(run.datasetId());
        // Factory 返回的正是 ExtractionEngine 创建 Chunker 时使用的注册实例，
        // 避免验收阶段用“同名但实现不同”的计数器重算 Token。
        TokenCounter counter = chunkers.requireTokenCounter(
                config.chunker().tokenizerId()
        );
        return new DatasetSession(run, config, dataset, counter);
    }

    /** 每次 Run 的观测只保存范围和计数，不保存 Source 或 Artifact 正文。 */
    private final class DatasetSession implements Session {
        private final RunSnapshot run;
        private final SpaceDocumentProcessingConfig config;
        private final ExtractionAcceptanceDataset dataset;
        private final TokenCounter counter;
        private final Map<String, ExtractionAcceptanceDataset.Case> casesBySource;
        private final List<ExtractionObservation> observations = new ArrayList<>();
        private final Set<String> observedCases = new HashSet<>();
        private String errorCode;

        private DatasetSession(
                RunSnapshot run,
                SpaceDocumentProcessingConfig config,
                ExtractionAcceptanceDataset dataset,
                TokenCounter counter
        ) {
            this.run = run;
            this.config = config;
            this.dataset = dataset;
            this.counter = counter;
            Map<String, ExtractionAcceptanceDataset.Case> indexed = new HashMap<>();
            for (var value : dataset.cases()) {
                if (indexed.putIfAbsent(value.sourceSha256(), value) != null) {
                    throw new IllegalStateException("dataset contains duplicate source hashes");
                }
            }
            casesBySource = Map.copyOf(indexed);
        }

        @Override
        public void observe(RunItem item, ExtractionResult result) {
            Objects.requireNonNull(item, "item must not be null");
            Objects.requireNonNull(result, "result must not be null");
            var expected = casesBySource.get(result.sourceSha256());
            if (expected == null) {
                errorCode = "DATASET_SOURCE_MISMATCH";
                return;
            }
            if (!observedCases.add(expected.id())) {
                errorCode = "DATASET_SOURCE_DUPLICATE";
                return;
            }
            observations.add(new ExtractionObservationFactory().create(
                    expected.id(),
                    result,
                    counter,
                    config.chunker()
            ));
        }

        @Override
        public ExtractionGateReport complete() {
            if (errorCode != null) {
                return errorReport(errorCode);
            }
            var report = new ExtractionAcceptanceRunner().run(dataset, observations);
            List<ExtractionGateReport.CaseResult> cases = report.cases().stream()
                    .map(value -> new ExtractionGateReport.CaseResult(
                            value.caseId(),
                            value.sourceSha256(),
                            value.passed(),
                            value.parseSuccessRate(),
                            value.tokenOverflowCount(),
                            value.invalidSourceSpanCount(),
                            value.sourceAccountingRate(),
                            value.silentTruncationCount(),
                            value.findings().stream().map(finding ->
                                    new ExtractionGateReport.Finding(
                                            finding.code(),
                                            finding.message()
                                    )
                            ).toList()
                    ))
                    .toList();
            return new ExtractionGateReport(
                    report.passed()
                            ? ExtractionGateStatus.PASSED
                            : ExtractionGateStatus.FAILED,
                    dataset.datasetVersion(),
                    report.datasetVersion(),
                    run.configSnapshot().fingerprint(),
                    clock.instant(),
                    cases,
                    null
            );
        }

        private ExtractionGateReport errorReport(String stableErrorCode) {
            return new ExtractionGateReport(
                    ExtractionGateStatus.ERROR,
                    dataset.datasetVersion(),
                    dataset.datasetVersion(),
                    run.configSnapshot().fingerprint(),
                    clock.instant(),
                    List.of(),
                    stableErrorCode
            );
        }
    }
}
