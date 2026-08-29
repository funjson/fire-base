package dev.infinityknowledge.ingestion.parser;

/**
 * 富文档解析前与解析过程中共同执行的资源预算。
 *
 * @param maximumSourceBytes 压缩文件或原始来源的最大字节数
 * @param maximumExpandedBytes 归档文件累计解压后的最大字节数
 * @param maximumPages PDF 最大页数
 * @param maximumElements 最多允许产出的结构元素数量
 * @param maximumTextCharacters 最多允许产出的文本字符数
 * @param maximumArchiveEntries Office 归档文件最大条目数
 * @param maximumCompressionRatio 允许的最大解压缩比
 */
public record DocumentParseLimits(
        int maximumSourceBytes,
        long maximumExpandedBytes,
        int maximumPages,
        int maximumElements,
        int maximumTextCharacters,
        int maximumArchiveEntries,
        int maximumCompressionRatio
) {

    /** 校验所有解析预算均为安全的正值。 */
    public DocumentParseLimits {
        if (maximumSourceBytes < 1) {
            throw new IllegalArgumentException("maximumSourceBytes must be positive");
        }
        if (maximumExpandedBytes < maximumSourceBytes) {
            throw new IllegalArgumentException("maximumExpandedBytes must cover maximumSourceBytes");
        }
        if (maximumPages < 1 || maximumElements < 1 || maximumTextCharacters < 1
                || maximumArchiveEntries < 1 || maximumCompressionRatio < 1) {
            throw new IllegalArgumentException("all parser budgets must be positive");
        }
    }

    /** 返回适合同步摄取 Worker 的保守企业默认值。 */
    public static DocumentParseLimits defaults() {
        return new DocumentParseLimits(
                25 * 1_024 * 1_024,
                100L * 1_024 * 1_024,
                500,
                20_000,
                10_000_000,
                10_000,
                100
        );
    }
}
