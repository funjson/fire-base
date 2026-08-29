package dev.infinityknowledge.compiler;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractiveKnowledgePageCompilerTest {

    @Test
    void compilesOnlyProvidedSourcesAndKeepsProvenance() {
        TenantId tenantId = new TenantId("demo");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        KnowledgeChunk first = chunk(tenantId, spaceId, 0, "订单服务调用库存服务");
        KnowledgeChunk second = chunk(tenantId, spaceId, 1, "超时后检查连接池");

        var result = new ExtractiveKnowledgePageCompiler().compile(
                new KnowledgePageCompiler.CompilationRequest(
                        tenantId,
                        spaceId,
                        "order-service",
                        "订单服务",
                        List.of(
                                new KnowledgePageCompiler.CompilationSource(first, 90),
                                new KnowledgePageCompiler.CompilationSource(second, 80)
                        )
                )
        );

        assertEquals(2, result.sources().size());
        assertTrue(result.markdown().contains(first.content()));
        assertTrue(result.markdown().contains(second.content()));
    }

    @Test
    void rejectsSourcesFromAnotherTenant() {
        TenantId tenantId = new TenantId("demo");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        KnowledgeChunk foreign = chunk(
                new TenantId("other"),
                spaceId,
                0,
                "不应进入页面"
        );

        assertThrows(SecurityException.class, () ->
                new KnowledgePageCompiler.CompilationRequest(
                        tenantId,
                        spaceId,
                        "order-service",
                        "订单服务",
                        List.of(new KnowledgePageCompiler.CompilationSource(foreign, 90))
                ));
    }

    private static KnowledgeChunk chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            int ordinal,
            String content
    ) {
        UUID elementId = UUID.randomUUID();
        return new KnowledgeChunk(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                new DocumentId(UUID.randomUUID()),
                UUID.randomUUID(),
                List.of(elementId),
                List.of(new ChunkSourceSpan(elementId, 0, content.length(), null)),
                ordinal,
                List.of("架构", "依赖"),
                content,
                "架构 / 依赖\n\n" + content,
                "hash-" + ordinal,
                Map.of()
        );
    }
}
