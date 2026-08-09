package dev.infinityknowledge.domain.evidence;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.DocumentId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 提供证据到原始文档修订和章节位置的稳定引用。
 *
 * @param documentId 文档标识
 * @param revisionId 修订标识
 * @param chunkId Chunk 标识
 * @param title 文档标题
 * @param sectionPath 章节路径
 * @param sourceUri 原始来源 URI
 */
public record Citation(
        DocumentId documentId,
        UUID revisionId,
        UUID chunkId,
        String title,
        List<String> sectionPath,
        String sourceUri
) {

    /**
     * 校验引用具有完整来源定位。
     */
    public Citation {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        title = DomainChecks.requiredText(title, "citation title", 512);
        sectionPath = List.copyOf(
                Objects.requireNonNull(sectionPath, "sectionPath must not be null")
        );
        sourceUri = DomainChecks.requiredText(sourceUri, "sourceUri", 2048);
    }
}

