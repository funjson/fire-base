package dev.infinityknowledge.spi.wiki;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.wiki.PageSourceReference;

import java.util.List;
import java.util.Objects;

/** Compiles governed source chunks into a provenance-bound page draft. */
@FunctionalInterface
public interface KnowledgePageCompiler {

    CompiledPage compile(CompilationRequest request);

    record CompilationRequest(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String slug,
            String title,
            List<CompilationSource> sources
    ) {
        public CompilationRequest {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            slug = DomainChecks.requiredText(slug, "slug", 256);
            title = DomainChecks.requiredText(title, "title", 512);
            sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("compilation requires at least one source");
            }
            if (sources.stream().anyMatch(source ->
                    !tenantId.equals(source.chunk().tenantId())
                            || !spaceId.equals(source.chunk().spaceId()))) {
                throw new SecurityException("compilation source crosses tenant or space boundary");
            }
        }
    }

    record CompilationSource(KnowledgeChunk chunk, int authority) {
        public CompilationSource {
            Objects.requireNonNull(chunk, "chunk must not be null");
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
        }
    }

    record CompiledPage(
            String summary,
            String markdown,
            List<PageSourceReference> sources,
            String contentHash,
            String compilerVersion,
            String generatedBy
    ) {
        public CompiledPage {
            summary = DomainChecks.requiredText(summary, "summary", 8_000);
            markdown = DomainChecks.requiredText(markdown, "markdown", 2_000_000);
            sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("compiled page requires provenance");
            }
            contentHash = DomainChecks.requiredText(contentHash, "contentHash", 128);
            compilerVersion = DomainChecks.requiredText(
                    compilerVersion,
                    "compilerVersion",
                    128
            );
            generatedBy = DomainChecks.requiredText(generatedBy, "generatedBy", 128);
        }
    }
}
