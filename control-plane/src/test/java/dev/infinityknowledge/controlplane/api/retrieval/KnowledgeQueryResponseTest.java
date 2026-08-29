package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.Citation;
import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化 Agent 检索 API 的标量 ID 和 generatedAt 契约。
 */
class KnowledgeQueryResponseTest {

    @Test
    void mapsDomainValueObjectsToStableWireValues() {
        UUID requestId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        Instant generatedAt = Instant.parse("2026-08-03T00:00:00Z");
        var bundle = new EvidenceBundle(
                requestId,
                traceId,
                new TenantId("tenant-a"),
                List.of(new Evidence(
                        UUID.randomUUID(),
                        "evidence",
                        0.9,
                        80,
                        Set.of(RetrievalChannel.KEYWORD),
                        new Citation(
                                new DocumentId(documentId),
                                revisionId,
                                chunkId,
                                "Title",
                                List.of("Section"),
                                "https://example.test/source"
                        )
                )),
                RetrievalTerminalStatus.SUFFICIENT,
                RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED,
                false,
                List.of(new KnowledgeSpaceId("space-a")),
                List.of("a".repeat(64)),
                List.of(),
                generatedAt
        );

        var response = KnowledgeQueryResponse.from(bundle);
        String json = JsonMapper.builder().build().writeValueAsString(response);

        assertEquals("tenant-a", response.tenantId());
        assertEquals(documentId.toString(), response.evidences().getFirst()
                .citation().documentId());
        assertEquals(generatedAt, response.generatedAt());
        assertTrue(json.contains("\"tenantId\":\"tenant-a\""));
        assertTrue(json.contains("\"generatedAt\":\"2026-08-03T00:00:00Z\""));
        assertFalse(json.contains("\"tenantId\":{\"value\""));
    }
}
