package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 保存模型产生的知识空间顺序及可观测模型标识。
 *
 * @param orderedSpaceIds 从最相关到最不相关的空间标识
 * @param provider Provider 标识，不含凭据
 * @param model 实际模型标识
 */
public record SpaceRoutingResult(
        List<KnowledgeSpaceId> orderedSpaceIds,
        String provider,
        String model
) {

    /** 拒绝空结果和重复空间；是否为授权候选的完整排列由执行层复核。 */
    public SpaceRoutingResult {
        orderedSpaceIds = List.copyOf(Objects.requireNonNull(
                orderedSpaceIds,
                "orderedSpaceIds must not be null"
        ));
        if (orderedSpaceIds.isEmpty()
                || new HashSet<>(orderedSpaceIds).size() != orderedSpaceIds.size()
                || orderedSpaceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "orderedSpaceIds must contain unique non-null values"
            );
        }
        provider = DomainChecks.requiredText(provider, "provider", 64);
        model = DomainChecks.requiredText(model, "model", 128);
    }
}
