package dev.infinityknowledge.agent.client;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Knowledge Runtime 返回给 Agent 的稳定证据 DTO。 */
public record KnowledgeSearchResponse(
        UUID requestId,
        UUID traceId,
        String tenantId,
        List<Evidence> evidences,
        boolean sufficient,
        List<String> warnings,
        Instant generatedAt
) {
    /** 防止反序列化后的集合被调用方修改。 */
    public KnowledgeSearchResponse {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        tenantId = requireText(tenantId, "tenantId");
        evidences = List.copyOf(Objects.requireNonNull(evidences, "evidences must not be null"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    }

    /** 单条可引用证据。 */
    public record Evidence(
            UUID id,
            String content,
            double relevance,
            int authority,
            List<String> channels,
            Citation citation
    ) {
        public Evidence {
            Objects.requireNonNull(id, "id must not be null");
            content = requireText(content, "content");
            if (!Double.isFinite(relevance) || relevance < 0D || relevance > 1D) {
                throw new IllegalArgumentException("relevance must be between 0 and 1");
            }
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
            channels = List.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
            Objects.requireNonNull(citation, "citation must not be null");
        }
    }

    /** 原始事实来源定位。 */
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
            documentId = requireText(documentId, "documentId");
            Objects.requireNonNull(revisionId, "revisionId must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            title = requireText(title, "title");
            sectionPath = List.copyOf(
                    Objects.requireNonNull(sectionPath, "sectionPath must not be null")
            );
            sourceUri = requireText(sourceUri, "sourceUri");
            sourceSpans = List.copyOf(Objects.requireNonNull(
                    sourceSpans,
                    "sourceSpans must not be null"
            ));
        }

        /** 兼容旧服务端未提供逐元素高亮范围的响应。 */
        public Citation(
                String documentId, UUID revisionId, UUID chunkId, String title,
                List<String> sectionPath, String sourceUri
        ) {
            this(documentId, revisionId, chunkId, title, sectionPath, sourceUri, List.of());
        }
    }

    /** 供 Agent 或前端跳转到原文精确位置的范围。 */
    public record SourceSpan(UUID elementId, int startOffset, int endOffset, Integer pageNumber) {
        public SourceSpan {
            Objects.requireNonNull(elementId, "elementId must not be null");
            if (startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("source span offsets are invalid");
            }
            if (pageNumber != null && pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber must be positive when present");
            }
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
