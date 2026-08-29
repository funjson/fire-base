package dev.infinityknowledge.controlplane.api.document.extraction;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtractionRunViewsTest {

    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    @Test
    @DisplayName("正式摄取详情返回任务创建时固化的逐文件业务身份")
    void exposesPublicationIdentityForIngestRun() {
        var item = ExtractionRunViews.Detail.from(
                snapshot(ExtractionMode.INGEST)
        ).items().getFirst();

        assertAll(
                () -> assertEquals("handbook/oncall", item.externalId()),
                () -> assertEquals("生产故障处理手册", item.title()),
                () -> assertEquals(93, item.authority())
        );
    }

    @Test
    @DisplayName("抽取测试不把内部占位发布属性暴露为正式业务身份")
    void hidesPublicationIdentityForTestOnlyRun() {
        var item = ExtractionRunViews.Detail.from(
                snapshot(ExtractionMode.TEST_ONLY)
        ).items().getFirst();

        assertAll(
                () -> assertNull(item.externalId()),
                () -> assertNull(item.title()),
                () -> assertNull(item.authority())
        );
    }

    private static ExtractionRunStore.RunSnapshot snapshot(
            ExtractionMode mode
    ) {
        var tenantId = new TenantId("tenant-a");
        var spaceId = new KnowledgeSpaceId("engineering");
        var source = new ExtractionRunStore.SourceAsset(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                tenantId,
                spaceId,
                "source-object",
                "storage-object",
                "runbook.md",
                "text/markdown",
                128L,
                "b".repeat(64),
                NOW
        );
        var item = new ExtractionRunStore.RunItem(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                source,
                new ExtractionRunStore.PublicationAttributes(
                        "handbook/oncall",
                        "生产故障处理手册",
                        93
                ),
                ExtractionRunStore.ItemStatus.SUCCEEDED,
                ExtractionRunStore.ItemStage.COMPLETED,
                null,
                null,
                null,
                null,
                null,
                NOW,
                NOW
        );
        return new ExtractionRunStore.RunSnapshot(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                tenantId,
                spaceId,
                mode,
                ExtractionRunStore.RunStatus.SUCCEEDED,
                "zh-CN",
                1L,
                configSnapshot(),
                null,
                null,
                ExtractionGateStatus.NOT_EVALUATED,
                null,
                new PrincipalId("admin"),
                null,
                NOW,
                NOW,
                NOW,
                NOW,
                NOW,
                List.of(item)
        );
    }

    private static ExtractionConfigSnapshot configSnapshot() {
        return new ExtractionConfigSnapshot(
                contract(),
                "identity-bytes-v1",
                Map.of("text/markdown", "markdown"),
                new ExtractionConfigSnapshot.Cleaning(
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.REMOVE,
                        CleaningAction.REMOVE,
                        CleaningAction.METADATA_ONLY
                ),
                new ExtractionConfigSnapshot.Chunker(
                        "structure",
                        "unicode-code-point",
                        64,
                        256,
                        512,
                        32,
                        "{}"
                ),
                "a".repeat(64)
        );
    }

    private static DocumentProcessingContract contract() {
        return DocumentProcessingContract.create(
                "extraction-pipeline/v1",
                "normalizer-schema/v1",
                Map.of("text/markdown", "markdown/v1"),
                "cleaner/v1",
                "chunker/v1"
        );
    }
}
