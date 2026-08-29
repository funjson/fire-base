package dev.infinityknowledge.spi.extraction;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证抽取任务只引用 Space 创建时固定的配置版本 1。 */
class ExtractionRunConfigVersionTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final PrincipalId ADMIN = new PrincipalId("admin");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void beginRequestOnlyAcceptsVersionOne() {
        assertEquals(1L, beginRequest(1L).configVersion());
        assertThrows(IllegalArgumentException.class, () -> beginRequest(0L));
        assertThrows(IllegalArgumentException.class, () -> beginRequest(2L));
    }

    @Test
    void runSnapshotOnlyAcceptsVersionOne() {
        assertEquals(1L, runSnapshot(1L).configVersion());
        assertThrows(IllegalArgumentException.class, () -> runSnapshot(0L));
        assertThrows(IllegalArgumentException.class, () -> runSnapshot(2L));
    }

    private static ExtractionRunStore.BeginRequest beginRequest(long version) {
        return new ExtractionRunStore.BeginRequest(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                ExtractionMode.TEST_ONLY,
                "zh-CN",
                version,
                configSnapshot(),
                null,
                null,
                ADMIN,
                NOW
        );
    }

    private static ExtractionRunStore.RunSnapshot runSnapshot(long version) {
        return new ExtractionRunStore.RunSnapshot(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                ExtractionMode.TEST_ONLY,
                ExtractionRunStore.RunStatus.QUEUED,
                "zh-CN",
                version,
                configSnapshot(),
                null,
                null,
                ExtractionGateStatus.NOT_EVALUATED,
                null,
                ADMIN,
                null,
                NOW,
                null,
                null,
                null,
                null,
                List.of()
        );
    }

    private static ExtractionConfigSnapshot configSnapshot() {
        return new ExtractionConfigSnapshot(
                DocumentProcessingContract.create(
                        "extraction-pipeline-v1",
                        "normalizer-schema-v1",
                        Map.of("text/markdown", "markdown-parser-v1"),
                        "cleaner-v1",
                        "chunker-v1"
                ),
                "identity-bytes-v1",
                Map.of("text/markdown", "markdown-structure"),
                new ExtractionConfigSnapshot.Cleaning(
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.REMOVE
                ),
                new ExtractionConfigSnapshot.Chunker(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        128,
                        256,
                        16,
                        "{}"
                ),
                "a".repeat(64)
        );
    }
}
