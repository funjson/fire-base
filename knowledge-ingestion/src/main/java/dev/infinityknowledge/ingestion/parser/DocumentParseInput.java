package dev.infinityknowledge.ingestion.parser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * 传递给唯一已选 Parser 的有界、不可变输入。
 *
 * @param revisionId 目标不可变文档修订
 * @param mediaType 已去除参数的规范媒体类型
 * @param fileName 仅用于选择 Parser 和记录元数据的原始文件名
 * @param sourceBytes 已通过大小约束的来源字节
 * @param limits 解析资源预算
 */
public record DocumentParseInput(
        UUID revisionId,
        String mediaType,
        String fileName,
        byte[] sourceBytes,
        DocumentParseLimits limits
) {

    /** 防御性复制来源字节，并拒绝不完整请求。 */
    public DocumentParseInput {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        sourceBytes = Objects.requireNonNull(sourceBytes, "sourceBytes must not be null").clone();
        limits = Objects.requireNonNull(limits, "limits must not be null");
        if (sourceBytes.length > limits.maximumSourceBytes()) {
            throw new DocumentParseException("source exceeds maximumSourceBytes");
        }
    }

    /** 为不可变、有界来源创建新的读取流。 */
    public InputStream openStream() {
        return new ByteArrayInputStream(sourceBytes);
    }

    /** 为必须接收字节数组的解析库返回防御性副本。 */
    @Override
    public byte[] sourceBytes() {
        return sourceBytes.clone();
    }
}
