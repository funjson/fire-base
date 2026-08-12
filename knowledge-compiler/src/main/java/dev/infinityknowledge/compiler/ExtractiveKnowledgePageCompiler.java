package dev.infinityknowledge.compiler;

import dev.infinityknowledge.domain.wiki.PageSourceReference;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic, low-cost compiler baseline. It never introduces claims not present in source
 * chunks and therefore remains useful as a safe fallback when a generative provider is absent.
 */
public final class ExtractiveKnowledgePageCompiler implements KnowledgePageCompiler {
    public static final String VERSION = "extractive-v1";

    @Override
    public CompiledPage compile(CompilationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<CompilationSource> ordered = request.sources().stream()
                .sorted(java.util.Comparator
                        .comparingInt(CompilationSource::authority).reversed()
                        .thenComparingInt(value -> value.chunk().ordinal()))
                .toList();
        String summary = ordered.getFirst().chunk().content();
        if (summary.length() > 500) {
            summary = summary.substring(0, 500).stripTrailing() + "…";
        }
        StringBuilder markdown = new StringBuilder("# ")
                .append(request.title())
                .append("\n\n")
                .append(summary)
                .append("\n");
        Set<String> renderedSections = new LinkedHashSet<>();
        List<PageSourceReference> provenance = new ArrayList<>();
        for (CompilationSource source : ordered) {
            String section = source.chunk().sectionPath().isEmpty()
                    ? "内容"
                    : source.chunk().sectionPath().getLast();
            if (renderedSections.add(section)) {
                markdown.append("\n## ").append(section).append("\n");
            }
            markdown.append("\n")
                    .append(source.chunk().content())
                    .append("\n\n")
                    .append("<!-- source:")
                    .append(source.chunk().id())
                    .append(" -->\n");
            provenance.add(new PageSourceReference(
                    source.chunk().documentId(),
                    source.chunk().revisionId(),
                    source.chunk().id(),
                    source.chunk().sectionPath(),
                    source.chunk().contentHash(),
                    source.authority()
            ));
        }
        return new CompiledPage(
                summary,
                markdown.toString(),
                provenance,
                sha256(markdown.toString()),
                VERSION,
                "extractive"
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
