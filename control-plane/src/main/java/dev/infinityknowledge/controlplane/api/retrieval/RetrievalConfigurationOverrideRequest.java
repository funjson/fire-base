package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationOverride;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 单次查询使用的强类型局部配置覆盖；未提供字段继承 Space 当前配置。 */
public record RetrievalConfigurationOverrideRequest(
        FirstRound firstRound,
        Branches branches,
        Reranker reranker,
        Coverage coverage,
        Integer maximumRetrievalAttempts,
        Map<String, Boolean> chainNodeEnables,
        CrossSpace crossSpace
) {

    /** 转换为领域覆盖对象；系统硬上限在与 Space 配置合并后统一校验。 */
    public RetrievalConfigurationOverride toDomain() {
        return new RetrievalConfigurationOverride(
                firstRound == null ? null : new RetrievalConfigurationOverride.FirstRoundOverride(
                        firstRound.termExpansionEnabled(),
                        firstRound.terminologyResourceId(),
                        firstRound.maximumExpansionTerms()
                ),
                branches == null ? null : branches.toDomain(),
                reranker == null ? null : new RetrievalConfigurationOverride.RerankerOverride(
                        reranker.enabled(), reranker.providerId(), reranker.modelId(),
                        reranker.candidateLimit(), reranker.outputTopK()
                ),
                coverage == null ? null : new RetrievalConfigurationOverride.CoverageOverride(
                        coverage.enabled(), coverage.providerId(), coverage.modelId(),
                        coverage.promptVersion(), coverage.memoryLimit(),
                        coverage.sufficiencyThreshold()
                ),
                maximumRetrievalAttempts,
                chainNodeEnables == null
                        ? null
                        : RetrievalConfigurationDto.chainNodeMap(chainNodeEnables, false),
                crossSpace == null ? null
                        : new RetrievalConfigurationOverride.CrossSpaceOverride(
                        crossSpace.enabled(), crossSpace.maximumSpaces()
                )
        );
    }

    /** 返回不改变 Space 配置的空覆盖。 */
    public static RetrievalConfigurationOverrideRequest empty() {
        return new RetrievalConfigurationOverrideRequest(
                null, null, null, null, null, null, null
        );
    }

    public record FirstRound(
            Boolean termExpansionEnabled,
            String terminologyResourceId,
            Integer maximumExpansionTerms
    ) {
    }

    public record Branches(
            Integer maximumVariantsPerAttempt,
            Integer maximumRetrievalBranches,
            Integer rrfConstant,
            Map<String, Branch> channels
    ) {
        RetrievalConfigurationOverride.BranchesOverride toDomain() {
            Map<RetrievalChannel, RetrievalConfigurationOverride.BranchOverride> mapped = null;
            if (channels != null) {
                EnumMap<RetrievalChannel, RetrievalConfigurationOverride.BranchOverride> values =
                        new EnumMap<>(RetrievalChannel.class);
                channels.forEach((key, value) -> {
                    Objects.requireNonNull(value, "branch override must not be null");
                    values.put(
                            RetrievalConfigurationDto.channel(key),
                            new RetrievalConfigurationOverride.BranchOverride(
                                    value.enabled(), value.topK(), value.rrfWeight()
                            )
                    );
                });
                mapped = Map.copyOf(values);
            }
            return new RetrievalConfigurationOverride.BranchesOverride(
                    maximumVariantsPerAttempt,
                    maximumRetrievalBranches,
                    rrfConstant,
                    mapped
            );
        }
    }

    public record Branch(Boolean enabled, Integer topK, Double rrfWeight) {
    }

    public record Reranker(
            Boolean enabled,
            String providerId,
            String modelId,
            Integer candidateLimit,
            Integer outputTopK
    ) {
    }

    public record Coverage(
            Boolean enabled,
            String providerId,
            String modelId,
            String promptVersion,
            Integer memoryLimit,
            Double sufficiencyThreshold
    ) {
    }

    public record CrossSpace(Boolean enabled, Integer maximumSpaces) {
    }
}
