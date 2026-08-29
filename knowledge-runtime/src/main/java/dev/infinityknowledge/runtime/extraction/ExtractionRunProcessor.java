package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingException;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.extraction.ExtractionRequest;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemStage;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunStatus;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.runtime.extraction.StoredExtractionSourceReader.SourceReadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 执行一次多文件 TEST_ONLY 抽取任务的纯 Java Processor。
 *
 * <p>该类型只读取 OSS、调用统一 {@link ExtractionEngine} 并保存聚合诊断。它没有
 * KnowledgeWriter、Document 或 Projection 依赖，从结构上保证 {@code TEST_ONLY}
 * 任务不能误发布。调度模块只需周期调用 {@link #pollOnce()}。</p>
 */
public final class ExtractionRunProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractionRunProcessor.class);
    private static final Pattern PREFIXED_ERROR_CODE = Pattern.compile(
            "^([A-Z][A-Z0-9_]{0,127}):"
    );

    private final ExtractionRunStore store;
    private final StoredExtractionSourceReader sourceReader;
    private final ExtractionEngine extractionEngine;
    private final ExtractionPreviewFactory previewFactory;
    private final ExtractionGateEvaluator gateEvaluator;
    private final DocumentParseLimits parseLimits;
    private final String workerId;
    private final Duration staleAfter;
    private final Duration receivingTimeout;
    private final Clock clock;

    /** 创建一次只处理一个 Run 的有界 Worker。 */
    public ExtractionRunProcessor(
            ExtractionRunStore store,
            ObjectStorage objectStorage,
            ExtractionEngine extractionEngine,
            ExtractionGateEvaluator gateEvaluator,
            DocumentParseLimits parseLimits,
            String workerId,
            Duration staleAfter,
            Duration receivingTimeout,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sourceReader = new StoredExtractionSourceReader(objectStorage, parseLimits);
        this.extractionEngine = Objects.requireNonNull(
                extractionEngine,
                "extractionEngine must not be null"
        );
        this.previewFactory = new ExtractionPreviewFactory();
        this.gateEvaluator = Objects.requireNonNull(
                gateEvaluator,
                "gateEvaluator must not be null"
        );
        this.parseLimits = Objects.requireNonNull(parseLimits, "parseLimits must not be null");
        this.workerId = requiredWorkerId(workerId);
        this.staleAfter = positive(staleAfter, "staleAfter");
        this.receivingTimeout = positive(receivingTimeout, "receivingTimeout");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 原子领取并处理最多一个任务。
     *
     * @return 本次是否领取到任务；返回 true 不代表任务一定成功
     */
    public boolean pollOnce() {
        Instant now = clock.instant();
        store.failInterruptedUploads(now.minus(receivingTimeout), now, 10);
        var claimed = store.claimNext(
                ExtractionMode.TEST_ONLY,
                workerId,
                now.minus(staleAfter),
                now
        );
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.orElseThrow());
        return true;
    }

    private void process(RunSnapshot run) {
        if (run.mode() != ExtractionMode.TEST_ONLY) {
            finish(run.id(), RunStatus.FAILED, "UNSUPPORTED_RUN_MODE");
            return;
        }
        if (run.status() == RunStatus.CANCEL_REQUESTED
                || store.cancellationRequested(run.id(), workerId)) {
            finish(run.id(), RunStatus.CANCELLED, null);
            return;
        }

        SpaceDocumentProcessingConfig config;
        try {
            config = ExtractionConfigSnapshots.restore(
                    run.tenantId(),
                    run.spaceId(),
                    run.configVersion(),
                    run.configSnapshot()
            );
            var verified = ExtractionConfigSnapshots.capture(
                    config,
                    run.configSnapshot().processingContract(),
                    run.configSnapshot().normalizerContract()
            );
            if (!verified.fingerprint().equals(run.configSnapshot().fingerprint())) {
                throw new IllegalStateException("stored extraction config fingerprint differs");
            }
            requireCurrentProcessingContract(run, config);
        } catch (DocumentProcessingContractMismatchException mismatch) {
            finish(
                    run.id(),
                    RunStatus.FAILED,
                    DocumentProcessingContractMismatchException.CODE
            );
            return;
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Extraction config snapshot is invalid: runId={}, failureType={}",
                    run.id(),
                    failure.getClass().getName()
            );
            finish(run.id(), RunStatus.FAILED, "CONFIG_SNAPSHOT_INVALID");
            return;
        }

        boolean anyFailed = run.items().stream()
                .anyMatch(item -> item.status() == ItemStatus.FAILED);
        GateContext gate = startGate(run, config);
        for (RunItem item : run.items()) {
            if (item.status() == ItemStatus.SUCCEEDED && gate.active()) {
                try {
                    gate.observe(item, extract(run, item, config));
                } catch (RuntimeException failure) {
                    gate.fail("GATE_OBSERVATION_FAILED", failure);
                }
                continue;
            }
            if (item.status() != ItemStatus.QUEUED && item.status() != ItemStatus.RUNNING) {
                continue;
            }
            if (store.cancellationRequested(run.id(), workerId)) {
                finish(run.id(), RunStatus.CANCELLED, null);
                return;
            }
            if (!store.heartbeat(run.id(), workerId, clock.instant())) {
                finishCancellationIfRequested(run.id());
                return;
            }
            ItemOutcome outcome = processItem(run, item, config, gate);
            if (outcome == ItemOutcome.LOST_OWNERSHIP) {
                finishCancellationIfRequested(run.id());
                return;
            }
            anyFailed |= outcome == ItemOutcome.FAILED;
        }

        if (store.cancellationRequested(run.id(), workerId)) {
            finish(run.id(), RunStatus.CANCELLED, null);
            return;
        }
        // 即使抽取 Run 失败，也让 Dataset Runner 对缺失观测给出独立 Gate 结论；
        // 不能用 Run 状态推断门禁状态，更不能把未观察到的 Case 当成通过。
        ExtractionGateReport gateReport = gate.complete();
        finish(
                run.id(),
                anyFailed ? RunStatus.FAILED : RunStatus.SUCCEEDED,
                anyFailed ? "ITEM_FAILED" : null,
                gateReport
        );
    }

    /** 在读取任何 Item 原件前，拒绝排队期间发生的实现合同漂移。 */
    private void requireCurrentProcessingContract(
            RunSnapshot run,
            SpaceDocumentProcessingConfig config
    ) {
        try {
            if (!run.configSnapshot().processingContract().equals(
                    extractionEngine.processingContract(config)
            )) {
                throw new DocumentProcessingContractMismatchException();
            }
        } catch (DocumentProcessingContractMismatchException mismatch) {
            throw mismatch;
        } catch (RuntimeException unavailableOrInvalid) {
            throw new DocumentProcessingContractMismatchException(
                    unavailableOrInvalid
            );
        }
    }

    private ItemOutcome processItem(
            RunSnapshot run,
            RunItem item,
            SpaceDocumentProcessingConfig config,
            GateContext gate
    ) {
        if (!store.startItem(run.id(), item.id(), workerId, clock.instant())) {
            return ItemOutcome.LOST_OWNERSHIP;
        }
        try {
            ExtractionResult result = extract(run, item, config);
            gate.observe(item, result);
            boolean persisted = store.succeedItem(
                    run.id(),
                    item.id(),
                    workerId,
                    ExtractionRunDiagnostics.from(result),
                    previewFactory.create(result),
                    clock.instant()
            );
            return persisted ? ItemOutcome.SUCCEEDED : ItemOutcome.LOST_OWNERSHIP;
        } catch (SemanticChunkingException failure) {
            return failItem(
                    run,
                    item,
                    ItemStage.CHUNK,
                    safeErrorCode(failure.code(), "SEMANTIC_CHUNKING_FAILED")
            );
        } catch (DocumentParseException failure) {
            return failItem(
                    run,
                    item,
                    ItemStage.PARSE,
                    prefixedErrorCode(failure.getMessage(), "DOCUMENT_PARSE_FAILED")
            );
        } catch (SourceReadException failure) {
            return failItem(run, item, ItemStage.STORED, failure.code());
        } catch (IllegalArgumentException failure) {
            return failItem(run, item, ItemStage.PARSE, "CONFIGURATION_INVALID");
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Unexpected extraction failure: runId={}, itemId={}, failureType={}",
                    run.id(),
                    item.id(),
                    failure.getClass().getName()
            );
            return failItem(run, item, ItemStage.PARSE, "EXTRACTION_FAILED");
        }
    }

    /** 读取同一不可变来源并执行创建时配置，供首次处理和门禁恢复共用。 */
    private ExtractionResult extract(
            RunSnapshot run,
            RunItem item,
            SpaceDocumentProcessingConfig config
    ) {
        byte[] source = sourceReader.read(item);
        return extractionEngine.extract(new ExtractionRequest(
                    run.tenantId(),
                    run.spaceId(),
                    new DocumentId(item.id()),
                    item.sourceAsset().mediaType(),
                    item.sourceAsset().fileName(),
                    run.language(),
                    run.configSnapshot().normalizerContract(),
                    source,
                    item.sourceAsset().checksumSha256(),
                    parseLimits,
                    config
            ));
    }

    private ItemOutcome failItem(
            RunSnapshot run,
            RunItem item,
            ItemStage stage,
            String errorCode
    ) {
        return store.failItem(
                run.id(), item.id(), workerId, stage, errorCode, clock.instant()
        ) ? ItemOutcome.FAILED : ItemOutcome.LOST_OWNERSHIP;
    }

    /**
     * 完成状态写入失败时，重新检查同一 Worker 是否刚收到取消请求。
     *
     * <p>这覆盖“最后一次取消检查之后、成功提交之前”的竞态；如果所有权已经被
     * 其他 Worker 接管，则不会越权修改任务。</p>
     */
    private boolean finish(UUID runId, RunStatus status, String errorCode) {
        return finish(runId, status, errorCode, null);
    }

    private boolean finish(
            UUID runId,
            RunStatus status,
            String errorCode,
            ExtractionGateReport gateReport
    ) {
        if (store.finishRun(
                runId,
                workerId,
                status,
                errorCode,
                gateReport,
                clock.instant()
        )) {
            return true;
        }
        if (status != RunStatus.CANCELLED
                && store.cancellationRequested(runId, workerId)
                && store.finishRun(
                        runId,
                        workerId,
                        RunStatus.CANCELLED,
                        null,
                        null,
                        clock.instant()
                )) {
            return true;
        }
        LOGGER.warn(
                "Extraction run finalization lost ownership: runId={}, intendedStatus={}",
                runId,
                status
        );
        return false;
    }

    private GateContext startGate(
            RunSnapshot run,
            SpaceDocumentProcessingConfig config
    ) {
        if (run.datasetId() == null) {
            return new GateContext(run, null);
        }
        try {
            return new GateContext(run, gateEvaluator.start(run, config));
        } catch (RuntimeException failure) {
            GateContext context = new GateContext(run, null);
            context.fail("GATE_INITIALIZATION_FAILED", failure);
            return context;
        }
    }

    /** 隔离门禁失败，确保真实抽取成功不会被误写为任务失败。 */
    private final class GateContext {
        private final RunSnapshot run;
        private final ExtractionGateEvaluator.Session session;
        private String errorCode;

        private GateContext(
                RunSnapshot run,
                ExtractionGateEvaluator.Session session
        ) {
            this.run = run;
            this.session = session;
        }

        private boolean active() {
            return session != null && errorCode == null;
        }

        private void observe(RunItem item, ExtractionResult result) {
            if (!active()) {
                return;
            }
            try {
                session.observe(item, result);
            } catch (RuntimeException failure) {
                fail("GATE_OBSERVATION_FAILED", failure);
            }
        }

        private ExtractionGateReport complete() {
            if (run.datasetId() == null) {
                return null;
            }
            if (errorCode == null) {
                try {
                    ExtractionGateReport report = session.complete();
                    if (report == null) {
                        throw new IllegalStateException("gate evaluator returned null");
                    }
                    return report;
                } catch (RuntimeException failure) {
                    fail("GATE_EVALUATION_FAILED", failure);
                }
            }
            return new ExtractionGateReport(
                    ExtractionGateStatus.ERROR,
                    run.datasetId(),
                    "unavailable",
                    run.configSnapshot().fingerprint(),
                    clock.instant(),
                    java.util.List.of(),
                    errorCode
            );
        }

        private void fail(String code, RuntimeException failure) {
            if (errorCode == null) {
                errorCode = code;
                LOGGER.error(
                        "Extraction gate failed: runId={}, failureType={}",
                        run.id(),
                        failure.getClass().getName()
                );
            }
        }
    }

    private void finishCancellationIfRequested(UUID runId) {
        if (store.cancellationRequested(runId, workerId)) {
            finish(runId, RunStatus.CANCELLED, null);
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** 只提取消息开头的稳定原因码，正文和其余异常消息不会离开进程。 */
    private static String prefixedErrorCode(String message, String fallback) {
        if (message == null) {
            return fallback;
        }
        var match = PREFIXED_ERROR_CODE.matcher(message);
        return match.find() ? match.group(1) : fallback;
    }

    /** 第三方异常只有满足本系统稳定码字符集时才能进入任务记录。 */
    private static String safeErrorCode(String code, String fallback) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : fallback;
    }

    private static String requiredWorkerId(String value) {
        Objects.requireNonNull(value, "workerId must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("workerId is blank or too long");
        }
        return normalized;
    }

    private enum ItemOutcome {
        SUCCEEDED,
        FAILED,
        LOST_OWNERSHIP
    }

}
