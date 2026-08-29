package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证发布前不会接受无法回到原始 Element 的来源范围。 */
class KnowledgeWriteBatchTest {

    private static final String PROCESSING_CONTRACT_FINGERPRINT = "c".repeat(64);

    @Test
    void rejectsSourceSpanThatExceedsTheSourceElementContent() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> writeBatch(1L, PROCESSING_CONTRACT_FINGERPRINT, 3)
        );
        assertTrue(failure.getMessage().contains("exceeds element content"));
    }

    /** 正式发布不能省略 Space 创建时固化的处理合同指纹。 */
    @Test
    void rejectsMissingProcessingContractFingerprint() {
        assertThrows(
                NullPointerException.class,
                () -> writeBatch(1L, null, 2)
        );
    }

    /** 正式发布只能引用当前不可变的 Space 配置版本 1。 */
    @Test
    void rejectsProcessingConfigVersionOtherThanOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> writeBatch(0L, PROCESSING_CONTRACT_FINGERPRINT, 2)
        );
    }

    private static KnowledgeWriteBatch writeBatch(
            long processingConfigVersion,
            String processingContractFingerprint,
            int spanEnd
    ) {
        UUID revisionId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        DocumentId documentId = DocumentId.random();
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        KnowledgeElement element = new KnowledgeElement(
                elementId, revisionId, null, ElementType.PARAGRAPH, 0,
                List.of(), "正文", Map.of()
        );
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.randomUUID(), tenantId, spaceId, documentId, revisionId,
                List.of(elementId), List.of(new ChunkSourceSpan(elementId, 0, spanEnd, null)),
                0, List.of(), "正文", "正文", "a".repeat(64), Map.of()
        );

        return new KnowledgeWriteBatch(
                document(documentId, tenantId, spaceId),
                new DocumentRevision(
                        revisionId, documentId, "b".repeat(64), "text/plain", "zh-CN",
                        "test-v1", Instant.parse("2026-08-14T00:00:00Z")
                ),
                List.of(element),
                List.of(chunk),
                null,
                null,
                processingConfigVersion,
                processingContractFingerprint
        );
    }

    private static KnowledgeDocument document(
            DocumentId documentId,
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        return new KnowledgeDocument(
                documentId, tenantId, spaceId, "文档",
                new SourceDescriptor("api", SourceType.API, "doc-1", "urn:test:doc-1", Map.of()),
                DocumentStatus.ACTIVE, 50, Map.of(), now, now
        );
    }
}
