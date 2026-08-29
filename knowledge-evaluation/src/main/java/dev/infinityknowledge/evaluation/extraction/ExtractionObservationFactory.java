package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Chunk;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Disposition;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Element;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.ProcessingContracts;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration.Action;
import dev.infinityknowledge.ingestion.cleaning.ElementCleaningDecision;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把真实 ExtractionEngine 结果转换为 Golden Runner 使用的归一化观测。
 *
 * <p>该工厂不接受调用方提交的 Token 数或来源范围。Token 数始终通过本次配置选择的
 * {@link TokenCounter} 对最终 {@code contextualText} 重算；Chunk 的 Element 内范围
 * 始终通过 Parser 产生的 typed Provenance 映射到 Artifact 坐标。</p>
 */
public final class ExtractionObservationFactory {

    /**
     * 从真实抽取结果生成成功观测，并校验 Tokenizer 与 Chunk 合同属于同一配置。
     *
     * @param caseId Dataset Case 标识
     * @param result ExtractionEngine 的真实返回结果
     * @param tokenCounter 本次 Chunker 实际选择的 TokenCounter 实例
     * @param chunkerConfiguration 本次运行的不可变 Chunk 配置快照
     */
    public ExtractionObservation create(
            String caseId,
            ExtractionResult result,
            TokenCounter tokenCounter,
            ChunkerConfiguration chunkerConfiguration
    ) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
        Objects.requireNonNull(
                chunkerConfiguration,
                "chunkerConfiguration must not be null"
        );
        validateTokenizer(result, tokenCounter, chunkerConfiguration);

        Map<UUID, ElementProvenance> provenance = indexProvenance(result);
        Map<UUID, KnowledgeElement> retainedElements = indexElements(result);
        Map<UUID, ElementCleaningDecision> decisions = indexDecisions(result);

        List<Element> elements = new ArrayList<>();
        List<SourceRange> removedRanges = new ArrayList<>();
        for (ElementCleaningDecision decision : result.cleaningDecisions()) {
            ElementProvenance source = require(provenance, decision.elementId(), "provenance");
            SourceRange range = range(source);
            if (decision.action() == Action.REMOVE) {
                removedRanges.add(range);
                elements.add(new Element(
                        range,
                        decision.elementType(),
                        decision.role(),
                        Disposition.REMOVED
                ));
                continue;
            }
            KnowledgeElement element = require(
                    retainedElements,
                    decision.elementId(),
                    "retained element"
            );
            elements.add(new Element(
                    range,
                    element.type(),
                    element.attributes().get("role"),
                    decision.action() == Action.KEEP
                            ? Disposition.INDEXABLE
                            : Disposition.METADATA_ONLY
            ));
            if (decision.elementType() != element.type()
                    || !Objects.equals(decision.role(), element.attributes().get("role"))) {
                throw new IllegalArgumentException(
                        "cleaning decision metadata differs from retained element"
                );
            }
        }

        List<Chunk> chunks = result.chunks().stream()
                .map(chunk -> new Chunk(
                        chunk.ordinal(),
                        chunk.sourceSpans().stream()
                                .map(span -> absoluteRange(
                                        span,
                                        provenance,
                                        retainedElements,
                                        decisions
                                ))
                                .toList(),
                        tokenCounter.count(chunk.contextualText()),
                        chunkerConfiguration.maximumTokens()
                ))
                .toList();

        var contracts = result.contracts();
        return new ExtractionObservation(
                caseId,
                result.sourceSha256(),
                result.artifact().sha256(),
                result.artifact().contract(),
                result.artifact().text().length(),
                new ProcessingContracts(
                        contracts.processorVersion(),
                        contracts.normalizer(),
                        contracts.parser(),
                        contracts.cleaner(),
                        contracts.chunker(),
                        tokenCounter.contract()
                ),
                true,
                elements,
                chunks,
                removedRanges,
                List.of()
        );
    }

    private static void validateTokenizer(
            ExtractionResult result,
            TokenCounter tokenCounter,
            ChunkerConfiguration configuration
    ) {
        if (!configuration.tokenizerId().equals(tokenCounter.id())) {
            throw new IllegalArgumentException(
                    "tokenCounter does not match the selected tokenizerId"
            );
        }
        String chunkerContract = result.contracts().chunker();
        if (!chunkerContract.contains("counter=" + tokenCounter.contract())
                || !chunkerContract.contains(
                        ":maximum=" + configuration.maximumTokens() + ':'
                )) {
            throw new IllegalArgumentException(
                    "tokenCounter or maximumTokens differs from the executed chunker contract"
            );
        }
    }

    private static SourceRange absoluteRange(
            ChunkSourceSpan span,
            Map<UUID, ElementProvenance> provenance,
            Map<UUID, KnowledgeElement> retainedElements,
            Map<UUID, ElementCleaningDecision> decisions
    ) {
        ElementProvenance source = require(provenance, span.elementId(), "provenance");
        KnowledgeElement element = require(retainedElements, span.elementId(), "retained element");
        ElementCleaningDecision decision = require(decisions, span.elementId(), "cleaning decision");
        if (decision.action() != Action.KEEP) {
            throw new IllegalArgumentException("chunk references a non-indexable element");
        }
        if (span.endOffset() > element.content().length()) {
            throw new IllegalArgumentException("chunk SourceSpan exceeds element content");
        }
        if (!Objects.equals(span.pageNumber(), source.pageNumber())) {
            throw new IllegalArgumentException(
                    "chunk pageNumber differs from typed element provenance"
            );
        }
        return new SourceRange(
                source.startOffset() + span.startOffset(),
                source.startOffset() + span.endOffset()
        );
    }

    private static Map<UUID, ElementProvenance> indexProvenance(ExtractionResult result) {
        Map<UUID, ElementProvenance> values = new HashMap<>();
        for (ElementProvenance provenance : result.elementProvenance()) {
            if (!result.artifact().id().equals(provenance.artifactId())
                    || values.putIfAbsent(provenance.elementId(), provenance) != null) {
                throw new IllegalArgumentException(
                        "extraction result contains invalid or duplicate provenance"
                );
            }
        }
        return Map.copyOf(values);
    }

    private static Map<UUID, KnowledgeElement> indexElements(ExtractionResult result) {
        Map<UUID, KnowledgeElement> values = new HashMap<>();
        for (KnowledgeElement element : result.retainedElements()) {
            if (values.putIfAbsent(element.id(), element) != null) {
                throw new IllegalArgumentException(
                        "extraction result contains duplicate retained elements"
                );
            }
        }
        return Map.copyOf(values);
    }

    private static Map<UUID, ElementCleaningDecision> indexDecisions(
            ExtractionResult result
    ) {
        Map<UUID, ElementCleaningDecision> values = new HashMap<>();
        for (ElementCleaningDecision decision : result.cleaningDecisions()) {
            if (values.putIfAbsent(decision.elementId(), decision) != null) {
                throw new IllegalArgumentException(
                        "extraction result contains duplicate cleaning decisions"
                );
            }
        }
        if (values.size() != result.elementProvenance().size()) {
            throw new IllegalArgumentException(
                    "cleaning decisions do not account for every parsed element"
            );
        }
        return Map.copyOf(values);
    }

    private static SourceRange range(ElementProvenance source) {
        return new SourceRange(source.startOffset(), source.endOffset());
    }

    private static <T> T require(Map<UUID, T> values, UUID id, String valueName) {
        T value = values.get(id);
        if (value == null) {
            throw new IllegalArgumentException("extraction result is missing " + valueName);
        }
        return value;
    }
}
