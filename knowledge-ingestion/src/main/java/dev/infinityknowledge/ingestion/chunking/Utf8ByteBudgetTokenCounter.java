package dev.infinityknowledge.ingestion.chunking;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 用 UTF-8 字节数作为 Chunk 大小预算代理的计数器。
 *
 * <p>该实现不对应任何模型 Tokenizer，也不承诺字节数是任意未知 Tokenizer 的数学
 * 上界。它只为未安装精确 Adapter 的部署提供确定性的容量代理，能力目录
 * 必须明确标记 {@link #exactModelTokens()} 为 false。</p>
 */
public final class Utf8ByteBudgetTokenCounter implements TokenCounter {
    /** Space 配置使用的稳定标识。 */
    public static final String ID = "UTF8_BYTE_BUDGET";
    /** 参与处理指纹的实现版本。 */
    public static final String VERSION = "utf8-byte-budget-v1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String description() {
        return "以 UTF-8 字节数作为确定性预算代理，不等同于模型精确 Token 数";
    }

    @Override
    public boolean exactModelTokens() {
        return false;
    }

    @Override
    public int count(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public int maximumPrefixEnd(
            String text,
            int startOffset,
            int endOffset,
            int maximumTokens
    ) {
        Objects.requireNonNull(text, "text must not be null");
        requireBudget(maximumTokens);
        requireRange(text, startOffset, endOffset);
        int used = 0;
        int index = startOffset;
        while (index < endOffset) {
            int codePoint = text.codePointAt(index);
            int width = utf8Width(codePoint);
            if (used + width > maximumTokens) {
                break;
            }
            used += width;
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static void requireRange(String text, int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset < startOffset || endOffset > text.length()) {
            throw new IllegalArgumentException("token counter range is invalid");
        }
        if (startOffset > 0 && startOffset < text.length()
                && Character.isLowSurrogate(text.charAt(startOffset))
                && Character.isHighSurrogate(text.charAt(startOffset - 1))) {
            throw new IllegalArgumentException("startOffset splits a Unicode surrogate pair");
        }
        if (endOffset > 0 && endOffset < text.length()
                && Character.isLowSurrogate(text.charAt(endOffset))
                && Character.isHighSurrogate(text.charAt(endOffset - 1))) {
            throw new IllegalArgumentException("endOffset splits a Unicode surrogate pair");
        }
    }

    private static void requireBudget(int maximumTokens) {
        if (maximumTokens < 0) {
            throw new IllegalArgumentException("maximumTokens must be non-negative");
        }
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
