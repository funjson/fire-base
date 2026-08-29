package dev.infinityknowledge.tokenizer.huggingface;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.ChunkSizing;
import dev.infinityknowledge.ingestion.chunking.StructuralKnowledgeChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HuggingFaceTokenCounterTest {
    private static final String SHA = "0".repeat(64);

    @Test
    void exposesPinnedModelContractAndFindsUnicodeSafePrefix() {
        try (var counter = HuggingFaceTokenCounter.forTesting(
                "BGE_M3_LOCAL",
                "local/bge-m3@1024",
                SHA,
                true,
                new CodePointBackend(2)
        )) {
            String text = "甲乙😀丙丁";

            assertThat(counter.exactModelTokens()).isTrue();
            assertThat(counter.modelProfileId()).isEqualTo("local/bge-m3@1024");
            assertThat(counter.contract())
                    .contains("sha256=" + SHA)
                    .contains("special=true")
                    .contains("modelProfile=local/bge-m3@1024");
            assertThat(counter.count(text)).isEqualTo(7);
            assertThat(counter.maximumPrefixEnd(text, 0, text.length(), 5))
                    .isEqualTo("甲乙😀".length());
        }
    }

    @Test
    void returnsStartWhenSpecialTokensAlreadyExceedBudget() {
        try (var counter = HuggingFaceTokenCounter.forTesting(
                "BGE_M3_LOCAL",
                "bge-m3-v1",
                SHA,
                true,
                new CodePointBackend(2)
        )) {
            assertThat(counter.maximumPrefixEnd("正文", 0, 2, 1)).isZero();
        }
    }

    @Test
    void rejectsRangesThatSplitSurrogatePair() {
        try (var counter = HuggingFaceTokenCounter.forTesting(
                "BGE_M3_LOCAL",
                "bge-m3-v1",
                SHA,
                false,
                new CodePointBackend(0)
        )) {
            assertThatThrownBy(() -> counter.maximumPrefixEnd("A😀B", 2, 4, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("surrogate pair");
        }
    }

    @Test
    void findsLargestPrefixWhenTokenCountsAreNotMonotonic() {
        try (var counter = HuggingFaceTokenCounter.forTesting(
                "BGE_M3_LOCAL",
                "bge-m3-v1",
                SHA,
                false,
                new NonMonotonicBackend()
        )) {
            // “a” 超预算，“ab” 因词表合并重新落入预算，“abc” 又超预算。
            assertThat(counter.maximumPrefixEnd("abc", 0, 3, 1)).isEqualTo(2);
        }
    }

    @Test
    void keepsPrefixAnalysisBoundedForVeryLongElements() {
        var backend = new BoundedAnalysisBackend();
        try (var counter = HuggingFaceTokenCounter.forTesting(
                "BGE_M3_LOCAL",
                "bge-m3-v1",
                SHA,
                false,
                backend
        )) {
            String text = "a".repeat(1_900_000);

            assertThat(counter.maximumPrefixEnd(text, 0, text.length(), 8))
                    .isEqualTo(8);
            assertThat(backend.analysisCalls).hasValue(1);
            assertThat(backend.countCalls).hasValue(2);
            assertThat(backend.analyzedCharacters).hasValue(256);
        }
    }

    @Test
    void keepsElementSlicingLinearAcrossTheFullLongElement() {
        var backend = new BoundedAnalysisBackend();
        try (var counter = HuggingFaceTokenCounter.forTesting(
                "BGE_M3_LOCAL",
                "bge-m3-v1",
                SHA,
                false,
                backend
        )) {
            int sourceCharacters = 1_900_000;
            String source = "a".repeat(sourceCharacters);
            UUID revisionId = UUID.randomUUID();
            KnowledgeElement element = new KnowledgeElement(
                    UUID.randomUUID(),
                    revisionId,
                    null,
                    ElementType.CODE,
                    0,
                    List.of(),
                    source,
                    Map.of()
            );
            var chunker = new StructuralKnowledgeChunker(new ChunkSizing(
                    counter,
                    2_048,
                    4_096,
                    4_096,
                    0
            ));

            var result = chunker.chunk(
                    new TenantId("tenant-a"),
                    new KnowledgeSpaceId("space-a"),
                    DocumentId.random(),
                    revisionId,
                    List.of(element)
            );

            assertThat(result.chunks()).hasSizeGreaterThan(400);
            assertThat(backend.analysisCalls.get()).isLessThan(500);
            assertThat(backend.analyzedCharacters.get())
                    .isLessThanOrEqualTo((long) sourceCharacters * 5);
        }
    }

    @Test
    void validatesConfiguredTokenizerFingerprintBeforeNativeLoad(@TempDir Path directory)
            throws Exception {
        Path tokenizer = directory.resolve("tokenizer.json");
        Files.writeString(tokenizer, "{}");

        assertThat(HuggingFaceTokenCounter.sha256(tokenizer))
                .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        assertThatThrownBy(() -> HuggingFaceTokenCounter.open(
                "BGE_M3_LOCAL",
                "bge-m3-v1",
                tokenizer,
                SHA,
                true
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void loadsPinnedLocalTokenizerFixtureWithoutNetwork() throws Exception {
        Path tokenizer = Path.of(Objects.requireNonNull(
                getClass().getResource("/tokenizers/word-level-tokenizer.json")
        ).toURI());
        String fingerprint = HuggingFaceTokenCounter.sha256(tokenizer);

        try (var counter = HuggingFaceTokenCounter.open(
                "LOCAL_WORD_LEVEL",
                "fixture/word-level@1",
                tokenizer,
                fingerprint,
                false
        )) {
            assertThat(counter.count("hello world")).isEqualTo(2);
            assertThat(counter.count("你好")).isEqualTo(1);
            assertThat(counter.maximumPrefixEnd("😀 hello", 0, 8, 1)).isEqualTo(2);
            assertThat(counter.version()).contains("sha256=" + fingerprint);
        }
    }

    private static final class CodePointBackend
            implements HuggingFaceTokenCounter.TokenizerBackend {
        private final int specialTokens;

        private CodePointBackend(int specialTokens) {
            this.specialTokens = specialTokens;
        }

        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length()) + specialTokens;
        }

        @Override
        public HuggingFaceTokenCounter.PrefixAnalysis analyzePrefix(
                String text,
                int maximumTokens
        ) {
            int codePoints = text.codePointCount(0, text.length());
            int accepted = Math.min(
                    codePoints,
                    Math.max(0, maximumTokens - specialTokens)
            );
            return new HuggingFaceTokenCounter.PrefixAnalysis(
                    codePoints + specialTokens,
                    text.offsetByCodePoints(0, accepted)
            );
        }

        @Override
        public void close() {
            // 测试替身没有外部资源。
        }
    }

    private static final class NonMonotonicBackend
            implements HuggingFaceTokenCounter.TokenizerBackend {

        @Override
        public int count(String text) {
            return switch (text) {
                case "" -> 0;
                case "a" -> 2;
                case "ab" -> 1;
                case "abc" -> 2;
                default -> throw new AssertionError("unexpected test input: " + text);
            };
        }

        @Override
        public HuggingFaceTokenCounter.PrefixAnalysis analyzePrefix(
                String text,
                int maximumTokens
        ) {
            int candidateEnd = "abc".equals(text) && maximumTokens == 1
                    ? 2
                    : Math.min(text.length(), maximumTokens);
            return new HuggingFaceTokenCounter.PrefixAnalysis(
                    count(text),
                    candidateEnd
            );
        }

        @Override
        public void close() {
            // 测试替身没有外部资源。
        }
    }

    private static final class BoundedAnalysisBackend
            implements HuggingFaceTokenCounter.TokenizerBackend {
        private final AtomicInteger countCalls = new AtomicInteger();
        private final AtomicInteger analysisCalls = new AtomicInteger();
        private final AtomicLong analyzedCharacters = new AtomicLong();

        @Override
        public int count(String text) {
            countCalls.incrementAndGet();
            return text.length();
        }

        @Override
        public HuggingFaceTokenCounter.PrefixAnalysis analyzePrefix(
                String text,
                int maximumTokens
        ) {
            analysisCalls.incrementAndGet();
            analyzedCharacters.addAndGet(text.length());
            return new HuggingFaceTokenCounter.PrefixAnalysis(
                    text.length(),
                    Math.min(text.length(), maximumTokens)
            );
        }

        @Override
        public void close() {
            // 测试替身没有外部资源。
        }
    }
}
