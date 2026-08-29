package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;

/**
 * 表示模型可用于排序的已授权知识空间摘要。
 *
 * <p>候选由服务端根据授权范围组装，模型只能调整顺序，不能生成新的空间标识。</p>
 *
 * @param spaceId 已授权空间标识
 * @param name 空间名称
 * @param description 空间用途描述，可为空
 */
public record SpaceRoutingCandidate(
        KnowledgeSpaceId spaceId,
        String name,
        String description
) {

    /** 校验发送给模型的空间摘要始终有界。 */
    public SpaceRoutingCandidate {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        name = DomainChecks.requiredText(name, "space name", 256);
        description = description == null ? "" : description.strip();
        if (description.length() > 2_000) {
            throw new IllegalArgumentException(
                    "space description must not exceed 2000 characters"
            );
        }
    }
}
