package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 发送给 Coverage Judge 的有限候选内容。
 *
 * <p>该对象刻意不携带 RRF、Retriever 或 Reranker 分数，避免模型用排序分数替代
 * 对证据内容本身的充分性判断。</p>
 */
public record CoverageCandidate(
        UUID candidateId,
        KnowledgeSpaceId spaceId,
        String title,
        List<String> sectionPath,
        String content
) {
    /** 校验 Judge 输入正文和结构信息均有明确上限。 */
    public CoverageCandidate {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        title = title == null ? "" : title.strip();
        if (title.length() > 512) {
            throw new IllegalArgumentException("candidate title must not exceed 512 characters");
        }
        sectionPath = List.copyOf(Objects.requireNonNull(
                sectionPath,
                "sectionPath must not be null"
        ));
        if (sectionPath.size() > 32
                || sectionPath.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 512)) {
            throw new IllegalArgumentException("candidate sectionPath is invalid");
        }
        content = DomainChecks.requiredText(content, "candidate content", 16_000);
    }
}
