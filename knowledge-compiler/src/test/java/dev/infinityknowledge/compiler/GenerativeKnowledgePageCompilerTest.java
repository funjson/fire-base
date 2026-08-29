package dev.infinityknowledge.compiler;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.PageSynthesisProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerativeKnowledgePageCompilerTest {

    @Test
    void acceptsReferencedProseAndStructuralParagraphs() {
        Fixture fixture = fixture();
        String markdown = "# Service\n\nSupported fact [source:" + fixture.reference()
                + "].\n\n- Related topics\n- Operations";

        var compiled = compiler(markdown, fixture.reference()).compile(fixture.request());

        assertEquals(markdown, compiled.markdown());
        assertEquals(1, compiled.sources().size());
    }

    @Test
    void rejectsUnknownInlineCitationEvenWhenDeclaredCoverageIsComplete() {
        Fixture fixture = fixture();
        String markdown = "Supported fact [source:" + fixture.reference()
                + "].\n\nUnsupported fact [source:unknown].";

        assertThrows(
                IllegalStateException.class,
                () -> compiler(markdown, fixture.reference()).compile(fixture.request())
        );
    }

    @Test
    void rejectsUnreferencedProseParagraph() {
        Fixture fixture = fixture();
        String markdown = "Supported fact [source:" + fixture.reference()
                + "].\n\nUnsupported fact without a citation.";

        assertThrows(
                IllegalStateException.class,
                () -> compiler(markdown, fixture.reference()).compile(fixture.request())
        );
    }

    private static GenerativeKnowledgePageCompiler compiler(
            String markdown,
            String reference
    ) {
        PageSynthesisProvider provider = request -> new PageSynthesisProvider.SynthesisResult(
                "Summary",
                markdown,
                List.of(reference)
        );
        return new GenerativeKnowledgePageCompiler(
                provider, "test-v1", "test-provider", 1.0D
        );
    }

    private static Fixture fixture() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        String content = "Supported fact.";
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.randomUUID(), tenantId, spaceId, documentId, revisionId,
                List.of(elementId),
                List.of(new ChunkSourceSpan(elementId, 0, content.length(), null)),
                0, List.of("Architecture"), content,
                "Architecture\n\n" + content, "content-hash", Map.of()
        );
        return new Fixture(
                chunk.id().toString(),
                new KnowledgePageCompiler.CompilationRequest(
                        tenantId,
                        spaceId,
                        "service",
                        "Service",
                        List.of(new KnowledgePageCompiler.CompilationSource(chunk, 90))
                )
        );
    }

    private record Fixture(
            String reference,
            KnowledgePageCompiler.CompilationRequest request
    ) {
    }
}
