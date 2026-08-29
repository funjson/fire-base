package dev.infinityknowledge.domain.common;

import java.util.Objects;

/**
 * 提供领域对象共享的字符串和数值校验，避免不同聚合使用不一致的空值语义。
 */
public final class DomainChecks {

    /**
     * 阻止实例化纯工具类。
     */
    private DomainChecks() {
    }

    /**
     * 校验并规范化必填字符串。
     *
     * @param value 原始字符串
     * @param field 字段名称
     * @param maxLength 最大允许长度
     * @return 去除首尾空白后的字符串
     */
    public static String requiredText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    /**
     * 校验必须原样保留的非空文本，不规范化首尾空白。
     *
     * <p>Chunk 正文及其上下文化文本必须与 SourceSpan、内容哈希使用同一份字节语义；
     * 如果在领域对象构造时再次 {@code strip}，引用范围与已计算指纹就会发生漂移。</p>
     *
     * @param value 原始字符串
     * @param field 字段名称
     * @param maxLength 最大允许长度
     * @return 未改写的原字符串
     */
    public static String requiredVerbatimText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    /**
     * 校验评分处于闭区间零到一之间。
     *
     * @param value 待校验评分
     * @param field 字段名称
     * @return 原评分
     */
    public static double unitScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
        return value;
    }
}
