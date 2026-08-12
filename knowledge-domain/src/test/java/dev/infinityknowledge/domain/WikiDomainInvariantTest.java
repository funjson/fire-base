package dev.infinityknowledge.domain;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.wiki.KnowledgePage;
import dev.infinityknowledge.domain.wiki.KnowledgePageId;
import dev.infinityknowledge.domain.wiki.KnowledgePageRevision;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import dev.infinityknowledge.domain.wiki.PageSourceReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WikiDomainInvariantTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void publishedPageRequiresActiveRevision() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgePage(
                new KnowledgePageId(UUID.randomUUID()),
                new TenantId("demo"),
                new KnowledgeSpaceId("engineering"),
                "order-service",
                "订单服务",
                KnowledgePageStatus.PUBLISHED,
                UUID.randomUUID(),
                null,
                1,
                NOW,
                NOW
        ));
    }

    @Test
    void compiledRevisionRequiresTraceableSources() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgePageRevision(
                UUID.randomUUID(),
                new KnowledgePageId(UUID.randomUUID()),
                1,
                "摘要",
                "# 页面",
                List.of(),
                "hash",
                "compiler-v1",
                "extractive",
                NOW
        ));
    }

    @Test
    void exposesMaximumSourceAuthority() {
        var pageId = new KnowledgePageId(UUID.randomUUID());
        var revision = new KnowledgePageRevision(
                UUID.randomUUID(),
                pageId,
                1,
                "摘要",
                "# 页面",
                List.of(source(60), source(90)),
                "hash",
                "compiler-v1",
                "extractive",
                NOW
        );

        assertEquals(90, revision.maximumSourceAuthority());
    }

    private static PageSourceReference source(int authority) {
        return new PageSourceReference(
                new DocumentId(UUID.randomUUID()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of("架构", "依赖"),
                "hash-" + authority,
                authority
        );
    }
}
