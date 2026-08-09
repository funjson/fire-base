package dev.infinityknowledge.domain.space;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 表示租户内用于治理和授权的知识空间标识。
 *
 * @param value 知识空间标识
 */
public record KnowledgeSpaceId(String value) {

    /**
     * 规范化知识空间标识。
     */
    public KnowledgeSpaceId {
        value = DomainChecks.requiredText(value, "knowledgeSpaceId", 64);
    }
}

