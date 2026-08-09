package dev.infinityknowledge.spi.embedding;

import java.util.List;
import java.util.Objects;

/**
 * 表示与输入序号对应的不可变向量结果。
 *
 * @param index 输入序号
 * @param values 浮点向量
 */
public record EmbeddingVector(int index, List<Double> values) {

    /**
     * 校验输入序号和有限向量值。
     */
    public EmbeddingVector {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        values = List.copyOf(Objects.requireNonNull(values, "values must not be null"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("embedding vector must not be empty");
        }
        if (values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("embedding vector contains invalid values");
        }
    }
}

