package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.domain.evidence.EvidenceBundle;

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
        List<String> warnings,
        Instant generatedAt
) {

    public KnowledgeQueryResponse {
        evidences = List.copyOf(evidences);
        warnings = List.copyOf(warnings);
    }

    public static KnowledgeQueryResponse from(EvidenceBundle bundle) {
        return new KnowledgeQueryResponse(
                bundle.requestId(),
                bundle.traceId(),
                bundle.tenantId().value(),
                bundle.evidences().stream().map(Evidence::from).toList(),
                bundle.sufficient(),
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
            String sourceUri
    ) {
        public Citation {
            sectionPath = List.copyOf(sectionPath);
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
                    citation.sourceUri()
            );
        }
    }
}
