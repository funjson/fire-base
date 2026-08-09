package dev.infinityknowledge.spi.embedding;

import java.util.List;

/**
 * 定义批量文本向量化能力，重试和速率限制由适配器内部处理。
 */
@FunctionalInterface
public interface EmbeddingProvider {

    /**
     * 按输入顺序生成向量。
     *
     * @param texts 非空文本列表
     * @param spec 固定模型和维度
     * @return 与输入一一对应的向量
     */
    List<EmbeddingVector> embed(List<String> texts, EmbeddingSpec spec);
}

