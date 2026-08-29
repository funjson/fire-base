package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 表示一次仅允许重排候选空间的模型路由请求。
 *
 * @param standaloneQuery 已由调用方消解完成的独立查询
 * @param allowedSpaces 服务端授权后的候选空间
 */
public record SpaceRoutingRequest(
        String standaloneQuery,
        List<SpaceRoutingCandidate> allowedSpaces
) {

    /** 防止重复空间或无界候选列表进入模型。 */
    public SpaceRoutingRequest {
        standaloneQuery = DomainChecks.requiredText(
                standaloneQuery,
                "standaloneQuery",
                16_000
        );
        allowedSpaces = List.copyOf(Objects.requireNonNull(
                allowedSpaces,
                "allowedSpaces must not be null"
        ));
        if (allowedSpaces.isEmpty() || allowedSpaces.size() > 100) {
            throw new IllegalArgumentException(
                    "allowedSpaces must contain between 1 and 100 candidates"
            );
        }
        Set<KnowledgeSpaceId> ids = new HashSet<>();
        for (SpaceRoutingCandidate candidate : allowedSpaces) {
            Objects.requireNonNull(candidate, "allowedSpaces must not contain null values");
            if (!ids.add(candidate.spaceId())) {
                throw new IllegalArgumentException("allowedSpaces must contain unique space ids");
            }
        }
    }
}
