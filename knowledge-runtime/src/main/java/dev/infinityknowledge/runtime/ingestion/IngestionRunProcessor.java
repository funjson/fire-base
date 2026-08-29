package dev.infinityknowledge.runtime.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingException;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.extraction.ExtractionRequest;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.runtime.extraction.ExtractionConfigSnapshots;
import dev.infinityknowledge.runtime.extraction.ExtractionPreviewFactory;
import dev.infinityknowledge.runtime.extraction.ExtractionRunDiagnostics;
import dev.infinityknowledge.runtime.extraction.StoredExtractionSourceReader;
import dev.infinityknowledge.runtime.extraction.StoredExtractionSourceReader.SourceReadException;
import dev.infinityknowledge.runtime.ingestion.DocumentPublicationService.PublicationRequest;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemStage;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunStatus;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.ingestion.PublishedSourceCatalog;
import dev.infinityknowledge.spi.ingestion.PublishedSourceCatalog.PublishedSource;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 执行一个正式多文件摄取任务，并把每个来源发布为不可变文档修订。
 *
 * <p>任务使用创建时配置快照，逐文件执行 OSS 完整性校验、无覆盖判重、统一抽取和
 * 事务发布。外部键冲突不会创建新修订；内容重复会返回既有文档。进入
 * {@link ItemStage#PUBLISHING} 后取消请求被 Store 明确拒绝，避免“写入成功但任务被
 * 标成取消”的不一致，也不需要额外 Lease/Fence 对象。</p>
 */
public final class IngestionRunProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionRunProcessor.class);
    private static final Pattern PREFIXED_ERROR_CODE = Pattern.compile(
            "^([A-Z][A-Z0-9_]{0,127}):"
    );

    private final ExtractionRunStore store;
    private final PublishedSourceCatalog sourceCatalog;
    private final ExtractionEngine extractionEngine;
    private final StoredExtractionSourceReader sourceReader;
    private final ExtractionPreviewFactory previewFactory;
    private final DocumentPublicationService publicationService;
    private final DocumentParseLimits parseLimits;
    private final String workerId;
    private final Duration staleAfter;
    private final Duration receivingTimeout;
    private final Clock clock;

    /** 创建每次最多领取一个正式 Run 的有界处理器。 */
    public IngestionRunProcessor(
            ExtractionRunStore store,
            PublishedSourceCatalog sourceCatalog,
            ObjectStorage objectStorage,
            ExtractionEngine extractionEngine,
            DocumentPublicationService publicationService,
            DocumentParseLimits parseLimits,
            String workerId,
            Duration staleAfter,
            Duration receivingTimeout,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sourceCatalog = Objects.requireNonNull(
                sourceCatalog,
                "sourceCatalog must not be null"
        );
        this.extractionEngine = Objects.requireNonNull(
                extractionEngine,
                "extractionEngine must not be null"
        );
        this.publicationService = Objects.requireNonNull(
                publicationService,
                "publicationService must not be null"
        );
        this.parseLimits = Objects.requireNonNull(parseLimits, "parseLimits must not be null");
        this.sourceReader = new StoredExtractionSourceReader(objectStorage, parseLimits);
        this.previewFactory = new ExtractionPreviewFactory();
        this.workerId = requiredWorkerId(workerId);
        this.staleAfter = positive(staleAfter, "staleAfter");
        this.receivingTimeout = positive(receivingTimeout, "receivingTimeout");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** 原子领取并处理至多一个 INGEST Run。 */
    public boolean pollOnce() {
        Instant now = clock.instant();
        store.failInterruptedUploads(now.minus(receivingTimeout), now, 10);
        Optional<RunSnapshot> claimed = store.claimNext(
                ExtractionMode.INGEST,
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
        if (run.mode() != ExtractionMode.INGEST) {
            finish(run.id(), RunStatus.FAILED, "UNSUPPORTED_RUN_MODE");
            return;
        }
        if (run.status() == RunStatus.CANCEL_REQUESTED
                || store.cancellationRequested(run.id(), workerId)) {
            finish(run.id(), RunStatus.CANCELLED, null);
            return;
        }
        SpaceDocumentProcessingConfig config = restoreConfig(run);
        if (config == null) {
            return;
        }

        boolean anyFailed = run.items().stream()
                .anyMatch(item -> item.status() == ItemStatus.FAILED);
        for (RunItem item : run.items()) {
            if (item.status() == ItemStatus.SUCCEEDED
                    || item.status() == ItemStatus.SKIPPED_DUPLICATE
                    || item.status() == ItemStatus.FAILED
                    || item.status() == ItemStatus.CANCELLED) {
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
            ItemOutcome outcome = processItem(run, item, config);
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
        finish(
                run.id(),
                anyFailed ? RunStatus.FAILED : RunStatus.SUCCEEDED,
                anyFailed ? "ITEM_FAILED" : null
        );
    }

    private SpaceDocumentProcessingConfig restoreConfig(RunSnapshot run) {
        try {
            SpaceDocumentProcessingConfig config = ExtractionConfigSnapshots.restore(
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
                throw new IllegalStateException("stored ingestion config fingerprint differs");
            }
            requireCurrentProcessingContract(run, config);
            return config;
        } catch (DocumentProcessingContractMismatchException mismatch) {
            finish(
                    run.id(),
                    RunStatus.FAILED,
                    DocumentProcessingContractMismatchException.CODE
            );
            return null;
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Ingestion config snapshot is invalid: runId={}, failureType={}",
                    run.id(),
                    failure.getClass().getName()
            );
            finish(run.id(), RunStatus.FAILED, "CONFIG_SNAPSHOT_INVALID");
            return null;
        }
    }

    /** 在查重、OSS 读取、模型调用和发布前复核当前实际实现合同。 */
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
            SpaceDocumentProcessingConfig config
    ) {
        boolean recoveringPublication = item.stage() == ItemStage.PUBLISHING;
        if (!recoveringPublication
                && !store.startItem(run.id(), item.id(), workerId, clock.instant())) {
            return ItemOutcome.LOST_OWNERSHIP;
        }
        try {
            String connectorId = ApiUploadSourceIdentity.connectorId(run.spaceId());
            Optional<PublishedSource> identityMatch = sourceCatalog.findByExternalId(
                    run.tenantId(),
                    run.spaceId(),
                    connectorId,
                    item.publication().externalId()
            );
            if (identityMatch.isPresent()) {
                PublishedSource existing = identityMatch.orElseThrow();
                if (!existing.contentHash().equals(item.sourceAsset().checksumSha256())) {
                    return failItem(run, item, ItemStage.PARSE, "EXTERNAL_ID_CONFLICT");
                }
                return skipDuplicate(run, item, existing);
            }
            Optional<PublishedSource> contentMatch = sourceCatalog.findByContentHash(
                    run.tenantId(),
                    run.spaceId(),
                    item.sourceAsset().checksumSha256()
            );
            if (contentMatch.isPresent()) {
                return skipDuplicate(run, item, contentMatch.orElseThrow());
            }

            var sourceDescriptor = ApiUploadSourceIdentity.descriptor(
                    run.spaceId(),
                    item.publication().externalId(),
                    item.sourceAsset().fileName(),
                    item.sourceAsset().mediaType()
            );
            DocumentId documentId = IngestionIdentity.documentId(
                    run.tenantId(),
                    run.spaceId(),
                    sourceDescriptor
            );
            byte[] source = sourceReader.read(item);
            ExtractionResult extraction = extractionEngine.extract(new ExtractionRequest(
                    run.tenantId(),
                    run.spaceId(),
                    documentId,
                    item.sourceAsset().mediaType(),
                    item.sourceAsset().fileName(),
                    run.language(),
                    run.configSnapshot().normalizerContract(),
                    source,
                    item.sourceAsset().checksumSha256(),
                    parseLimits,
                    config
            ));
            var diagnostics = ExtractionRunDiagnostics.from(extraction);
            var preview = previewFactory.create(extraction);
            if (!recoveringPublication && !store.beginPublication(
                    run.id(), item.id(), workerId, clock.instant()
            )) {
                return ItemOutcome.LOST_OWNERSHIP;
            }
            var asset = item.sourceAsset();
            var sourceObject = new SourceObjectReference(
                    extraction.revisionId(),
                    asset.storageId(),
                    asset.fileName(),
                    asset.mediaType(),
                    asset.contentLength(),
                    asset.checksumSha256(),
                    asset.storedAt()
            );
            var publication = publicationService.publish(
                    new PublicationRequest(
                            documentId,
                            run.tenantId(),
                            run.spaceId(),
                            sourceDescriptor,
                            item.publication().title(),
                            run.language(),
                            item.publication().authority(),
                            Map.of(
                                    "originalFileName", asset.fileName(),
                                    "mediaType", asset.mediaType(),
                                    "extractionRunId", run.id().toString()
                            ),
                            config.chunker().providerId(),
                            sourceObject,
                            null
                    ),
                    extraction,
                    run.configVersion(),
                    run.configSnapshot().processingContract()
            );
            boolean persisted = store.succeedPublishedItem(
                    run.id(),
                    item.id(),
                    workerId,
                    diagnostics,
                    preview,
                    publication.writeResult().documentId(),
                    publication.writeResult().revisionId(),
                    clock.instant()
            );
            return persisted ? ItemOutcome.SUCCEEDED : ItemOutcome.LOST_OWNERSHIP;
        } catch (DocumentProcessingContractMismatchException failure) {
            return failItem(
                    run,
                    item,
                    ItemStage.PUBLISHING,
                    DocumentProcessingContractMismatchException.CODE
            );
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
                    "Unexpected ingestion failure: runId={}, itemId={}, stage={}, failureType={}",
                    run.id(),
                    item.id(),
                    recoveringPublication ? ItemStage.PUBLISHING : item.stage(),
                    failure.getClass().getName()
            );
            return failItem(
                    run,
                    item,
                    recoveringPublication ? ItemStage.PUBLISHING : ItemStage.PARSE,
                    recoveringPublication ? "PUBLICATION_FAILED" : "INGESTION_FAILED"
            );
        }
    }

    private ItemOutcome skipDuplicate(
            RunSnapshot run,
            RunItem item,
            PublishedSource existing
    ) {
        boolean persisted = store.skipDuplicateItem(
                run.id(),
                item.id(),
                workerId,
                existing.documentId(),
                existing.revisionId(),
                clock.instant()
        );
        return persisted ? ItemOutcome.SUCCEEDED : ItemOutcome.LOST_OWNERSHIP;
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

    private boolean finish(UUID runId, RunStatus status, String errorCode) {
        if (store.finishRun(
                runId,
                workerId,
                status,
                errorCode,
                null,
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
                "Ingestion run finalization lost ownership: runId={}, intendedStatus={}",
                runId,
                status
        );
        return false;
    }

    private void finishCancellationIfRequested(UUID runId) {
        if (store.cancellationRequested(runId, workerId)) {
            finish(runId, RunStatus.CANCELLED, null);
        }
    }

    private static String prefixedErrorCode(String message, String fallback) {
        if (message == null) {
            return fallback;
        }
        var match = PREFIXED_ERROR_CODE.matcher(message);
        return match.find() ? match.group(1) : fallback;
    }

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

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private enum ItemOutcome {
        SUCCEEDED,
        FAILED,
        LOST_OWNERSHIP
    }
}
