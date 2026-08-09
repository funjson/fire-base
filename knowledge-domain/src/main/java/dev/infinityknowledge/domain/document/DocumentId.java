package dev.infinityknowledge.domain.document;

import java.util.Objects;
import java.util.UUID;

/**
 * 表示跨修订保持稳定的知识文档标识。
 *
 * @param value UUID 值
 */
public record DocumentId(UUID value) {

    /**
     * 拒绝空文档标识。
     */
    public DocumentId {
        Objects.requireNonNull(value, "documentId must not be null");
    }

    /**
     * 创建随机文档标识。
     *
     * @return 新文档标识
     */
    public static DocumentId random() {
        return new DocumentId(UUID.randomUUID());
    }
}

