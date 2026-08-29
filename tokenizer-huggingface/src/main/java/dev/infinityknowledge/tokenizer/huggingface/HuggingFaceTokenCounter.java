package dev.infinityknowledge.tokenizer.huggingface;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 使用本地、固定且校验过指纹的 HuggingFace {@code tokenizer.json} 精确计数。
 *
 * <p>运行期禁止从 HuggingFace Hub 下载模型；部署必须提供本地文件、SHA-256，并由运维
 * 固定绑定 Embedding Profile。文件指纹、DJL 版本、特殊 Token 规则与模型绑定都会进入
 * 处理契约，避免 Tokenizer 被静默替换后继续复用旧修订。</p>
 */
public final class HuggingFaceTokenCounter implements TokenCounter, AutoCloseable {
    private static final String IMPLEMENTATION_VERSION = "djl-huggingface-tokenizers-0.36.0";
    private static final Pattern STABLE_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern PROFILE_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._/@-]{0,255}"
    );
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAXIMUM_PREFIX_REFINEMENT_STEPS = 16;
    private static final int MINIMUM_PREFIX_WINDOW_CODE_POINTS = 256;
    private static final int CODE_POINTS_PER_REQUESTED_TOKEN = 4;
    private static final int MAXIMUM_PREFIX_WINDOW_CODE_POINTS = 262_144;

    private final String id;
    private final String modelProfileId;
    private final String tokenizerSha256;
    private final boolean addSpecialTokens;
    private final TokenizerBackend backend;

    private HuggingFaceTokenCounter(
            String id,
            String modelProfileId,
            String tokenizerSha256,
            boolean addSpecialTokens,
            TokenizerBackend backend
    ) {
        this.id = requireStableId(id);
        this.modelProfileId = requireProfileId(modelProfileId);
        this.tokenizerSha256 = requireSha256(tokenizerSha256);
        this.addSpecialTokens = addSpecialTokens;
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
    }

    /**
     * 从本地文件创建计数器；文件指纹不匹配时在加载原生 Tokenizer 前立即失败。
     */
    public static HuggingFaceTokenCounter open(
            String id,
            String modelProfileId,
            Path tokenizerJson,
            String expectedSha256,
            boolean addSpecialTokens
    ) {
        Objects.requireNonNull(tokenizerJson, "tokenizerJson must not be null");
        String normalizedExpected = requireSha256(expectedSha256);
        String actual = sha256(tokenizerJson);
        if (!normalizedExpected.equals(actual)) {
            throw new IllegalArgumentException("tokenizer.json SHA-256 does not match configuration");
        }
        try {
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerJson)
                    .optAddSpecialTokens(addSpecialTokens)
                    .optTruncation(false)
                    .optPadding(false)
                    .build();
            return new HuggingFaceTokenCounter(
                    id,
                    modelProfileId,
                    actual,
                    addSpecialTokens,
                    new DjlTokenizerBackend(tokenizer, addSpecialTokens)
            );
        } catch (IOException | RuntimeException loadFailure) {
            throw new IllegalStateException(
                    "HuggingFace tokenizer could not be loaded from the configured local file",
                    loadFailure
            );
        }
    }

    static HuggingFaceTokenCounter forTesting(
            String id,
            String modelProfileId,
            String tokenizerSha256,
            boolean addSpecialTokens,
            TokenizerBackend backend
    ) {
        return new HuggingFaceTokenCounter(
                id,
                modelProfileId,
                tokenizerSha256,
                addSpecialTokens,
                backend
        );
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String version() {
        return String.join(
                ":",
                IMPLEMENTATION_VERSION,
                "sha256=" + tokenizerSha256,
                "special=" + addSpecialTokens
        );
    }

    @Override
    public String description() {
        return "使用本地固定 tokenizer.json 精确计数，运维固定绑定模型配置 "
                + modelProfileId;
    }

    @Override
    public boolean exactModelTokens() {
        return true;
    }

    @Override
    public String modelProfileId() {
        return modelProfileId;
    }

    @Override
    public int count(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return backend.count(text);
    }

    @Override
    public int maximumPrefixEnd(
            String text,
            int startOffset,
            int endOffset,
            int maximumTokens
    ) {
        Objects.requireNonNull(text, "text must not be null");
        requireRange(text, startOffset, endOffset);
        if (maximumTokens < 0) {
            throw new IllegalArgumentException("maximumTokens must be non-negative");
        }
        if (startOffset == endOffset || countRange(text, startOffset, startOffset) > maximumTokens) {
            return startOffset;
        }
        /*
         * Slicer 会对同一 Element 的后续范围重复调用本方法，因此禁止每次编码全部
         * remainder。窗口按预算起步并有界扩大；即使极端压缩文本仍未达到预算，也只
         * 返回已经验证安全的窗口，不为追求理论最大前缀退化成 O(n²)。
         */
        int totalCodePoints = text.codePointCount(startOffset, endOffset);
        int maximumWindow = Math.min(
                totalCodePoints,
                MAXIMUM_PREFIX_WINDOW_CODE_POINTS
        );
        int windowCodePoints = Math.min(
                maximumWindow,
                initialWindowCodePoints(maximumTokens)
        );
        while (true) {
            int windowEnd = text.offsetByCodePoints(startOffset, windowCodePoints);
            String window = text.substring(startOffset, windowEnd);
            PrefixAnalysis analysis = backend.analyzePrefix(window, maximumTokens);
            if (analysis.totalTokens() > maximumTokens) {
                return startOffset + refineCandidate(window, maximumTokens, analysis);
            }
            if (windowCodePoints == totalCodePoints
                    || windowCodePoints == maximumWindow) {
                return windowEnd;
            }
            windowCodePoints = Math.min(
                    maximumWindow,
                    Math.multiplyExact(windowCodePoints, 2)
            );
        }
    }

    private int refineCandidate(
            String window,
            int maximumTokens,
            PrefixAnalysis analysis
    ) {
        int candidateEnd = requireCandidateOffset(window, analysis.candidateEnd());
        int candidateBudget = maximumTokens;
        for (int step = 0; step < MAXIMUM_PREFIX_REFINEMENT_STEPS; step++) {
            int candidateTokens = backend.count(window.substring(0, candidateEnd));
            if (candidateTokens <= maximumTokens) {
                return candidateEnd;
            }
            int overflow = candidateTokens - maximumTokens;
            candidateBudget = Math.max(0, candidateBudget - Math.max(1, overflow));
            String candidate = window.substring(0, candidateEnd);
            int refinedEnd = requireCandidateOffset(
                    candidate,
                    backend.analyzePrefix(candidate, candidateBudget).candidateEnd()
            );
            if (refinedEnd >= candidateEnd) {
                refinedEnd = candidateEnd == 0
                        ? 0
                        : candidate.offsetByCodePoints(candidateEnd, -1);
            }
            if (refinedEnd == candidateEnd) {
                break;
            }
            candidateEnd = refinedEnd;
        }
        throw new IllegalStateException(
                "tokenizer prefix refinement did not converge within the bounded budget"
        );
    }

    private static int initialWindowCodePoints(int maximumTokens) {
        long requested = Math.max(
                MINIMUM_PREFIX_WINDOW_CODE_POINTS,
                (long) maximumTokens * CODE_POINTS_PER_REQUESTED_TOKEN
        );
        return (int) Math.min(MAXIMUM_PREFIX_WINDOW_CODE_POINTS, requested);
    }

    private int countRange(String text, int startOffset, int endOffset) {
        return backend.count(text.substring(startOffset, endOffset));
    }

    private static int requireCandidateOffset(String text, int candidateEnd) {
        if (candidateEnd < 0 || candidateEnd > text.length()
                || splitsSurrogatePair(text, candidateEnd)) {
            throw new IllegalStateException("tokenizer prefix analysis returned an invalid offset");
        }
        return candidateEnd;
    }

    @Override
    public void close() {
        backend.close();
    }

    static String sha256(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException readFailure) {
            throw new IllegalArgumentException("tokenizer.json cannot be read", readFailure);
        }
    }

    private static String requireStableId(String value) {
        Objects.requireNonNull(value, "id must not be null");
        String normalized = value.strip();
        if (!STABLE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("token counter id has invalid format");
        }
        return normalized;
    }

    private static String requireProfileId(String value) {
        Objects.requireNonNull(value, "modelProfileId must not be null");
        String normalized = value.strip();
        if (!PROFILE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("modelProfileId has invalid format");
        }
        return normalized;
    }

    private static String requireSha256(String value) {
        Objects.requireNonNull(value, "tokenizerSha256 must not be null");
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException("tokenizerSha256 must be 64 lowercase hex characters");
        }
        return normalized;
    }

    private static void requireRange(String text, int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset < startOffset || endOffset > text.length()) {
            throw new IllegalArgumentException("token counter range is invalid");
        }
        if (splitsSurrogatePair(text, startOffset) || splitsSurrogatePair(text, endOffset)) {
            throw new IllegalArgumentException("token counter range splits a Unicode surrogate pair");
        }
    }

    private static boolean splitsSurrogatePair(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    interface TokenizerBackend extends AutoCloseable {
        int count(String text);

        /** 一次分析返回总 Token 数，以及不超过预算 Token span 的候选结束偏移。 */
        PrefixAnalysis analyzePrefix(String text, int maximumTokens);

        @Override
        void close();
    }

    record PrefixAnalysis(int totalTokens, int candidateEnd) {

        PrefixAnalysis {
            if (totalTokens < 0 || candidateEnd < 0) {
                throw new IllegalArgumentException("prefix analysis values must be non-negative");
            }
        }
    }

    private record DjlTokenizerBackend(
            HuggingFaceTokenizer tokenizer,
            boolean addSpecialTokens
    ) implements TokenizerBackend {
        private DjlTokenizerBackend {
            Objects.requireNonNull(tokenizer, "tokenizer must not be null");
        }

        @Override
        public synchronized int count(String text) {
            return tokenizer.encode(text, addSpecialTokens, false).getIds().length;
        }

        @Override
        public synchronized PrefixAnalysis analyzePrefix(String text, int maximumTokens) {
            Encoding encoding = tokenizer.encode(text, addSpecialTokens, false);
            int totalTokens = encoding.getIds().length;
            if (totalTokens <= maximumTokens) {
                return new PrefixAnalysis(totalTokens, text.length());
            }
            CharSpan[] spans = encoding.getCharTokenSpans();
            int codePointEnd = 0;
            int inspected = Math.min(maximumTokens, spans.length);
            for (int index = 0; index < inspected; index++) {
                CharSpan span = spans[index];
                if (span != null) {
                    codePointEnd = Math.max(codePointEnd, span.getEnd());
                }
            }
            int codePoints = text.codePointCount(0, text.length());
            if (codePointEnd > codePoints) {
                throw new IllegalStateException(
                        "tokenizer returned a character span outside the input"
                );
            }
            return new PrefixAnalysis(
                    totalTokens,
                    text.offsetByCodePoints(0, codePointEnd)
            );
        }

        @Override
        public void close() {
            tokenizer.close();
        }
    }
}
