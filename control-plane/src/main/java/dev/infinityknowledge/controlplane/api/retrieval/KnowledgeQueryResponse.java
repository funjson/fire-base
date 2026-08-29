package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 面向 Agent 和控制台的稳定检索响应，不暴露领域 Value Object 的序列化形态。
 */
public record KnowledgeQueryResponse(
        UUID requestId,
        UUID traceId,
        String tenantId,
        List<Evidence> evidences,
        boolean sufficient,
        String terminalStatus,
        String stopReason,
        boolean degraded,
        List<String> visitedSpaceIds,
        List<String> configurationFingerprints,
        List<String> warnings,
        Instant generatedAt
) {

    public KnowledgeQueryResponse {
        evidences = List.copyOf(evidences);
        visitedSpaceIds = List.copyOf(visitedSpaceIds);
        configurationFingerprints = List.copyOf(configurationFingerprints);
        warnings = List.copyOf(warnings);
    }

    public static KnowledgeQueryResponse from(EvidenceBundle bundle) {
        return new KnowledgeQueryResponse(
                bundle.requestId(),
                bundle.traceId(),
                bundle.tenantId().value(),
                bundle.evidences().stream().map(Evidence::from).toList(),
                bundle.sufficient(),
                bundle.terminalStatus().name(),
                bundle.stopReason().name(),
                bundle.degraded(),
                bundle.visitedSpaceIds().stream()
                        .map(dev.infinityknowledge.domain.space.KnowledgeSpaceId::value)
                        .toList(),
                bundle.configurationFingerprints(),
                bundle.warnings(),
                bundle.generatedAt()
        );
    }

    public record Evidence(
            UUID id,
            String content,
            double relevance,
            int authority,
            List<String> channels,
            Citation citation
    ) {
        private static Evidence from(
                dev.infinityknowledge.domain.evidence.Evidence evidence
        ) {
            return new Evidence(
                    evidence.id(),
                    evidence.content(),
                    evidence.relevance(),
                    evidence.authority(),
                    evidence.channels().stream()
                            .map(Enum::name)
                            .sorted()
                            .toList(),
                    Citation.from(evidence.citation())
            );
        }
    }

    public record Citation(
            String documentId,
            UUID revisionId,
            UUID chunkId,
            String title,
            List<String> sectionPath,
            String sourceUri,
            List<SourceSpan> sourceSpans
    ) {
        public Citation {
            sectionPath = List.copyOf(sectionPath);
            sourceSpans = List.copyOf(sourceSpans);
        }

        private static Citation from(
                dev.infinityknowledge.domain.evidence.Citation citation
        ) {
            return new Citation(
                    citation.documentId().value().toString(),
                    citation.revisionId(),
                    citation.chunkId(),
                    citation.title(),
                    citation.sectionPath(),
                    citation.sourceUri(),
                    citation.sourceSpans().stream().map(SourceSpan::from).toList()
            );
        }

    }

    /** 前端高亮所需的元素内文本范围；版面坐标由后续解析适配器扩展。 */
    public record SourceSpan(UUID elementId, int startOffset, int endOffset, Integer pageNumber) {
        private static SourceSpan from(ChunkSourceSpan span) {
            return new SourceSpan(
                    span.elementId(), span.startOffset(), span.endOffset(), span.pageNumber()
            );
        }
    }
}
