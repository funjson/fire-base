package dev.infinityknowledge.connector.obsidian;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Obsidian 元数据保留、忽略目录和分页快照语义。
 */
class ObsidianVaultConnectorTest {

    /**
     * 验证 Frontmatter、WikiLink、标签和嵌入附件可以进入来源元数据。
     *
     * @param vault JUnit 临时 Vault
     * @throws IOException 写入测试文件失败
     */
    @Test
    void extractsObsidianMetadata(@TempDir Path vault) throws IOException {
        Files.writeString(vault.resolve("订单服务.md"), """
                ---
                title: 订单服务
                tags: [architecture, order]
                owner: 研发一组
                ---
                # 订单服务架构
                依赖 [[库存服务]] 和 ![[order-flow.png]]。
                #production
                """);
        Path configDirectory = Files.createDirectory(vault.resolve(".obsidian"));
        Files.writeString(configDirectory.resolve("ignored.md"), "# 不应读取");
        ObsidianVaultConnector connector = connector(vault);

        var batch = connector.pull(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                ConnectorCursor.initial(),
                10
        );

        assertEquals(1, batch.records().size());
        var record = batch.records().getFirst();
        assertEquals("订单服务", record.title());
        assertTrue(record.metadata().get("obsidian.wikilinks").contains("库存服务"));
        assertTrue(record.metadata().get("obsidian.embeds").contains("order-flow.png"));
        assertTrue(record.metadata().get("obsidian.tags").contains("production"));
        assertFalse(batch.hasMore());
    }

    /**
     * 验证分页游标不会重复或跳过按路径排序的文档。
     *
     * @param vault JUnit 临时 Vault
     * @throws IOException 写入测试文件失败
     */
    @Test
    void pagesStableVaultSnapshot(@TempDir Path vault) throws IOException {
        Files.writeString(vault.resolve("b.md"), "# B");
        Files.writeString(vault.resolve("a.md"), "# A");
        ObsidianVaultConnector connector = connector(vault);

        var first = connector.pull(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                ConnectorCursor.initial(),
                1
        );
        var second = connector.pull(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                first.nextCursor(),
                1
        );

        assertEquals("a.md", first.records().getFirst().source().externalId());
        assertEquals("b.md", second.records().getFirst().source().externalId());
        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
    }

    @Test
    void doesNotShiftCursorWhenVaultChangesDuringRun(@TempDir Path vault)
            throws IOException {
        Files.writeString(vault.resolve("b.md"), "# B");
        Files.writeString(vault.resolve("c.md"), "# C");
        ObsidianVaultConnector connector = connector(vault);

        var first = connector.pull(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                ConnectorCursor.initial(),
                1
        );
        Files.writeString(vault.resolve("a.md"), "# A");
        var second = connector.pull(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                first.nextCursor(),
                1
        );

        assertEquals("b.md", first.records().getFirst().source().externalId());
        assertEquals("c.md", second.records().getFirst().source().externalId());
        assertFalse(second.hasMore());
    }

    /**
     * 创建测试连接器。
     *
     * @param vault Vault 路径
     * @return 连接器
     */
    private ObsidianVaultConnector connector(Path vault) {
        return new ObsidianVaultConnector(new ObsidianConnectorConfig(
                "obsidian-main",
                "研发知识库",
                vault,
                1_000_000,
                Set.of(".obsidian", ".trash")
        ));
    }
}
