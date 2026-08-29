package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;
import java.util.UUID;

/**
 * 表示一个 Space 当前真正参与检索的已发布索引代际。
 *
 * @param spaceId 索引所属 Space
 * @param generationId 不可变索引代际标识
 * @param configurationVersion 数据、Embedding、规范化与 Chunk 合同的稳定版本
 */
public record ActiveIndexGeneration(
        KnowledgeSpaceId spaceId,
        UUID generationId,
        String configurationVersion
) {
    /** 保证观测版本来自持久化代际事实且适合作为有限维度。 */
    public ActiveIndexGeneration {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(generationId, "generationId must not be null");
        configurationVersion = DomainChecks.requiredText(
                configurationVersion,
                "configurationVersion",
                128
        );
        if (!configurationVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(
                    "configurationVersion contains unsafe characters"
            );
        }
    }
}
