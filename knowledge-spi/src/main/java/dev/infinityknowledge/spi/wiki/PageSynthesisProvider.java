package dev.infinityknowledge.spi.wiki;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.List;
import java.util.Objects;

/** Optional generative provider used by a page compiler without leaking vendor SDK types. */
@FunctionalInterface
public interface PageSynthesisProvider {

    SynthesisResult synthesize(SynthesisRequest request);

    record SynthesisRequest(String title, List<SourceExcerpt> sources) {
        public SynthesisRequest {
            title = DomainChecks.requiredText(title, "title", 512);
            sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("synthesis requires sources");
            }
        }
    }

    record SourceExcerpt(String referenceId, String section, String content, int authority) {
        public SourceExcerpt {
            referenceId = DomainChecks.requiredText(referenceId, "referenceId", 128);
            section = section == null ? "" : section.strip();
            content = DomainChecks.requiredText(content, "content", 100_000);
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
        }
    }

    record SynthesisResult(String summary, String markdown, List<String> usedReferenceIds) {
        public SynthesisResult {
            summary = DomainChecks.requiredText(summary, "summary", 8_000);
            markdown = DomainChecks.requiredText(markdown, "markdown", 2_000_000);
            usedReferenceIds = List.copyOf(Objects.requireNonNull(
                    usedReferenceIds,
                    "usedReferenceIds must not be null"
            ));
            if (usedReferenceIds.isEmpty()) {
                throw new IllegalArgumentException("synthesis must identify used sources");
            }
        }
    }
}
