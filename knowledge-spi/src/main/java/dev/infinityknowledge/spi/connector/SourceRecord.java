package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.SourceDescriptor;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 表示连接器从外部系统读取的一份不可变原始记录。
 *
 * @param source 来源定位
 * @param title 标题
 * @param mediaType 媒体类型
 * @param content 原始文本；二进制附件使用对象存储引用
 * @param contentHash 内容 SHA-256
 * @param metadata 外部元数据
 * @param modifiedAt 外部更新时间
 * @param deleted 是否为删除墓碑
 */
public record SourceRecord(
        SourceDescriptor source,
        String title,
        String mediaType,
        String content,
        String contentHash,
        Map<String, String> metadata,
        Instant modifiedAt,
        boolean deleted
) {

    /**
     * 校验来源记录，并允许删除墓碑省略正文。
     */
    public SourceRecord {
        Objects.requireNonNull(source, "source must not be null");
        title = DomainChecks.requiredText(title, "title", 512);
        mediaType = DomainChecks.requiredText(mediaType, "mediaType", 128);
        if (deleted) {
            content = content == null ? "" : content;
        } else {
            content = DomainChecks.requiredText(content, "content", 10_000_000);
        }
        contentHash = DomainChecks.requiredText(contentHash, "contentHash", 128);
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        Objects.requireNonNull(modifiedAt, "modifiedAt must not be null");
    }
}

