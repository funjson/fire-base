package dev.infinityknowledge.ingestion.extraction;

import java.util.Objects;

/**
 * 一次抽取实际使用的完整处理契约。
 *
 * <p>这些值共同决定修订身份。它们只描述实现和有效参数，不包含 Space 配置的
 * 固化存储标记，因此相同有效配置始终产生相同处理身份。</p>
 *
 * @param pipeline 抽取编排算法版本
 * @param normalizer 来源规范化契约
 * @param parser Parser 实现与版本契约
 * @param cleaner 清洗规则与有效参数契约
 * @param chunker Chunker、Tokenizer 与有效参数契约
 * @param processorVersion 供修订持久化的稳定短指纹
 */
public record ExtractionContracts(
        String pipeline,
        String normalizer,
        String parser,
        String cleaner,
        String chunker,
        String processorVersion
) {

    /** 拒绝缺失的处理契约，避免生成不可解释的修订身份。 */
    public ExtractionContracts {
        pipeline = required(pipeline, "pipeline");
        normalizer = required(normalizer, "normalizer");
        parser = required(parser, "parser");
        cleaner = required(cleaner, "cleaner");
        chunker = required(chunker, "chunker");
        processorVersion = required(processorVersion, "processorVersion");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
