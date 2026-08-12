package dev.infinityknowledge.compiler;

import dev.infinityknowledge.domain.wiki.PageSourceReference;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.PageSynthesisProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provenance-enforcing compiler around a generative provider. Unknown citations, duplicate
 * citations and insufficient source coverage are rejected before a draft can be persisted.
 */
public final class GenerativeKnowledgePageCompiler implements KnowledgePageCompiler {
    private static final Pattern CITATION = Pattern.compile(
            "\\[source:([^\\]\\r\\n]{1,128})]"
    );
    private static final Pattern HEADING = Pattern.compile("#{1,6}\\s+.+");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?:[-*+]\\s+|\\d+[.)]\\s+).+"
    );
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?:-{3,}|\\*{3,}|_{3,})");
    private final PageSynthesisProvider provider;
    private final String compilerVersion;
    private final String generatedBy;
    private final double minimumSourceCoverage;

    public GenerativeKnowledgePageCompiler(
            PageSynthesisProvider provider,
            String compilerVersion,
            String generatedBy,
            double minimumSourceCoverage
    ) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.compilerVersion = required(compilerVersion, "compilerVersion");
        this.generatedBy = required(generatedBy, "generatedBy");
        if (!Double.isFinite(minimumSourceCoverage)
                || minimumSourceCoverage <= 0.0D
                || minimumSourceCoverage > 1.0D) {
            throw new IllegalArgumentException(
                    "minimumSourceCoverage must be greater than zero and at most one"
            );
        }
        this.minimumSourceCoverage = minimumSourceCoverage;
    }

    @Override
    public CompiledPage compile(CompilationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, CompilationSource> sourcesById = new HashMap<>();
        List<PageSynthesisProvider.SourceExcerpt> excerpts = request.sources().stream()
                .map(source -> {
                    String id = source.chunk().id().toString();
                    if (sourcesById.putIfAbsent(id, source) != null) {
                        throw new IllegalArgumentException("duplicate compilation source " + id);
                    }
                    return new PageSynthesisProvider.SourceExcerpt(
                            id,
                            String.join(" / ", source.chunk().sectionPath()),
                            source.chunk().content(),
                            source.authority()
                    );
                })
                .toList();
        PageSynthesisProvider.SynthesisResult synthesis = provider.synthesize(
                new PageSynthesisProvider.SynthesisRequest(request.title(), excerpts)
        );
        Set<String> usedIds = new HashSet<>(synthesis.usedReferenceIds());
        if (usedIds.size() != synthesis.usedReferenceIds().size()) {
            throw new IllegalStateException("synthesis returned duplicate source references");
        }
        if (!sourcesById.keySet().containsAll(usedIds)) {
            throw new IllegalStateException("synthesis referenced an unknown source");
        }
        validateMarkdownCitations(synthesis.markdown(), usedIds);
        double coverage = (double) usedIds.size() / sourcesById.size();
        if (coverage < minimumSourceCoverage) {
            throw new IllegalStateException("synthesis source coverage is below policy");
        }
        List<PageSourceReference> provenance = synthesis.usedReferenceIds().stream()
                .map(sourcesById::get)
                .map(source -> new PageSourceReference(
                        source.chunk().documentId(),
                        source.chunk().revisionId(),
                        source.chunk().id(),
                        source.chunk().sectionPath(),
                        source.chunk().contentHash(),
                        source.authority()
                ))
                .toList();
        return new CompiledPage(
                synthesis.summary(),
                synthesis.markdown(),
                provenance,
                sha256(synthesis.markdown()),
                compilerVersion,
                generatedBy
        );
    }

    /** Ensures every prose paragraph is backed by a declared source reference. */
    private static void validateMarkdownCitations(String markdown, Set<String> usedIds) {
        Set<String> cited = new HashSet<>();
        Matcher matcher = CITATION.matcher(markdown);
        while (matcher.find()) {
            String reference = matcher.group(1);
            if (!usedIds.contains(reference)) {
                throw new IllegalStateException(
                        "synthesis markdown referenced an unknown source"
                );
            }
            cited.add(reference);
        }
        if (!cited.containsAll(usedIds)) {
            throw new IllegalStateException(
                    "synthesis did not cite every declared source"
            );
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String value = paragraph.strip();
            if (value.isEmpty() || structuralParagraph(value)) {
                continue;
            }
            if (!CITATION.matcher(value).find()) {
                throw new IllegalStateException(
                        "synthesis contains an unreferenced prose paragraph"
                );
            }
        }
    }

    private static boolean structuralParagraph(String paragraph) {
        return paragraph.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .allMatch(line -> HEADING.matcher(line).matches()
                        || LIST_ITEM.matcher(line).matches()
                        || HORIZONTAL_RULE.matcher(line).matches());
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1..128 characters");
        }
        return normalized;
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
