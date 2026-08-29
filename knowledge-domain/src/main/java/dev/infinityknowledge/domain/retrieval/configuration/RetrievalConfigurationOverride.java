package dev.infinityknowledge.domain.retrieval.configuration;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.ChainNode;

import java.util.EnumMap;
import java.util.Map;

/**
 * 表示受信请求对 Space 检索配置的强类型局部覆盖。
 *
 * <p>{@code null} 只表示该字段继承 Space 当前修订；通道和 Chain 节点
 * Map 只覆盖其中显式出现的项。覆盖对象不保存到 Space，也不能绕过
 * {@link RetrievalConfigurationHardLimits}。</p>
 *
 * @param firstRound 首轮术语增强覆盖
 * @param branches 物理分支与 RRF 覆盖
 * @param reranker 精排覆盖
 * @param coverage Coverage 覆盖
 * @param maximumRetrievalAttempts 可选请求级检索尝试上限
 * @param chainNodeEnables 按固定 Chain 节点提供的局部开关覆盖
 * @param crossSpace 跨 Space 覆盖
 */
public record RetrievalConfigurationOverride(
        FirstRoundOverride firstRound,
        BranchesOverride branches,
        RerankerOverride reranker,
        CoverageOverride coverage,
        Integer maximumRetrievalAttempts,
        Map<ChainNode, Boolean> chainNodeEnables,
        CrossSpaceOverride crossSpace
) {
    /** 校验覆盖字段的绝对边界；跨字段关系在合并完整配置后校验。 */
    public RetrievalConfigurationOverride {
        if (maximumRetrievalAttempts != null
                && (maximumRetrievalAttempts < 1 || maximumRetrievalAttempts > 16)) {
            throw new IllegalArgumentException(
                    "maximumRetrievalAttempts override must be between 1 and 16"
            );
        }
        if (chainNodeEnables != null) {
            chainNodeEnables = immutableChainNodeOverrides(chainNodeEnables);
        }
    }

    /** 返回不改变 Space 配置的空覆盖。 */
    public static RetrievalConfigurationOverride empty() {
        return new RetrievalConfigurationOverride(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * 首轮术语增强局部覆盖。
     *
     * @param termExpansionEnabled 可选开关；Q0 不受此字段影响
     * @param terminologyResourceId 可选受控术语资源标识
     * @param maximumExpansionTerms 可选扩展术语数量
     */
    public record FirstRoundOverride(
            Boolean termExpansionEnabled,
            String terminologyResourceId,
            Integer maximumExpansionTerms
    ) {
        /** 规范化显式提供的覆盖值。 */
        public FirstRoundOverride {
            if (terminologyResourceId != null) {
                terminologyResourceId = stableId(
                        terminologyResourceId,
                        "terminologyResourceId override",
                        128
                );
            }
            if (maximumExpansionTerms != null
                    && (maximumExpansionTerms < 0 || maximumExpansionTerms > 64)) {
                throw new IllegalArgumentException(
                        "maximumExpansionTerms override must be between 0 and 64"
                );
            }
        }
    }

    /**
     * 分支预算和 Weighted RRF 局部覆盖。
     *
     * @param maximumVariantsPerAttempt 可选 Variant 上限
     * @param maximumRetrievalBranches 可选物理分支上限
     * @param rrfConstant 可选公共 RRF 平滑常数
     * @param channels 按通道提供的局部覆盖
     */
    public record BranchesOverride(
            Integer maximumVariantsPerAttempt,
            Integer maximumRetrievalBranches,
            Integer rrfConstant,
            Map<RetrievalChannel, BranchOverride> channels
    ) {
        /** 校验单字段绝对上限并复制通道 Map。 */
        public BranchesOverride {
            requireRange(
                    maximumVariantsPerAttempt,
                    1,
                    16,
                    "maximumVariantsPerAttempt override"
            );
            requireRange(
                    maximumRetrievalBranches,
                    1,
                    64,
                    "maximumRetrievalBranches override"
            );
            requireRange(rrfConstant, 1, 10_000, "rrfConstant override");
            if (channels != null) {
                if (channels.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getValue() == null)) {
                    throw new IllegalArgumentException(
                            "channel overrides must not contain null keys or values"
                    );
                }
                EnumMap<RetrievalChannel, BranchOverride> copy =
                        new EnumMap<>(RetrievalChannel.class);
                copy.putAll(channels);
                channels = Map.copyOf(copy);
            }
        }
    }

    /**
     * 单个物理通道的局部覆盖。
     *
     * @param enabled 可选启用状态
     * @param topK 可选候选深度
     * @param rrfWeight 可选 Weighted RRF 权重
     */
    public record BranchOverride(Boolean enabled, Integer topK, Double rrfWeight) {
        /** 校验显式提供的通道覆盖。 */
        public BranchOverride {
            requireRange(topK, 1, 1_000, "branch topK override");
            if (rrfWeight != null && (!Double.isFinite(rrfWeight)
                    || rrfWeight < 0.0D || rrfWeight > 100.0D)) {
                throw new IllegalArgumentException(
                        "rrfWeight override must be finite and between 0 and 100"
                );
            }
        }
    }

    /**
     * 固定精排节点局部覆盖。
     *
     * @param enabled 可选启用状态
     * @param providerId 可选 Provider 标识
     * @param modelId 可选模型标识
     * @param candidateLimit 可选精排候选窗口
     * @param outputTopK 可选精排输出窗口
     */
    public record RerankerOverride(
            Boolean enabled,
            String providerId,
            String modelId,
            Integer candidateLimit,
            Integer outputTopK
    ) {
        /** 规范化实现标识并校验窗口的单字段边界。 */
        public RerankerOverride {
            if (providerId != null) {
                providerId = stableId(providerId, "reranker providerId override", 64);
            }
            if (modelId != null) {
                modelId = stableId(modelId, "reranker modelId override", 128);
            }
            requireRange(candidateLimit, 1, 1_000, "reranker candidateLimit override");
            requireRange(outputTopK, 1, 1_000, "reranker outputTopK override");
        }
    }

    /**
     * Coverage Judge 和有限记忆局部覆盖。
     *
     * @param enabled 可选启用状态
     * @param providerId 可选 Judge Provider 标识
     * @param modelId 可选 Judge 模型标识
     * @param promptVersion 可选 Prompt 版本
     * @param memoryLimit 可选有限记忆上限
     * @param sufficiencyThreshold 可选充分性阈值
     */
    public record CoverageOverride(
            Boolean enabled,
            String providerId,
            String modelId,
            String promptVersion,
            Integer memoryLimit,
            Double sufficiencyThreshold
    ) {
        /** 规范化合同标识并校验显式阈值。 */
        public CoverageOverride {
            if (providerId != null) {
                providerId = stableId(providerId, "coverage providerId override", 64);
            }
            if (modelId != null) {
                modelId = stableId(modelId, "coverage modelId override", 128);
            }
            if (promptVersion != null) {
                promptVersion = stableId(
                        promptVersion,
                        "coverage promptVersion override",
                        128
                );
            }
            requireRange(memoryLimit, 1, 100, "coverage memoryLimit override");
            if (sufficiencyThreshold != null) {
                DomainChecks.unitScore(
                        sufficiencyThreshold,
                        "coverage sufficiencyThreshold override"
                );
            }
        }
    }

    /**
     * 跨 Space 局部覆盖。
     *
     * @param enabled 可选启用状态
     * @param maximumSpaces 可选 Space 总数上限，包含起始 Space
     */
    public record CrossSpaceOverride(Boolean enabled, Integer maximumSpaces) {
        /** 校验显式放大上限。 */
        public CrossSpaceOverride {
            requireRange(maximumSpaces, 1, 16, "crossSpace maximumSpaces override");
        }
    }

    private static Map<ChainNode, Boolean> immutableChainNodeOverrides(
            Map<ChainNode, Boolean> values
    ) {
        if (values.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException(
                    "chain node overrides must not contain null keys or values"
            );
        }
        EnumMap<ChainNode, Boolean> copy = new EnumMap<>(ChainNode.class);
        copy.putAll(values);
        return Map.copyOf(copy);
    }

    private static void requireRange(Integer value, int minimum, int maximum, String name) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
            );
        }
    }

    private static String stableId(String value, String field, int maximumLength) {
        String normalized = DomainChecks.requiredText(value, field, maximumLength);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }
}
