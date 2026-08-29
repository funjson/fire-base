package dev.infinityknowledge.domain.retrieval.configuration;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 表示一次检索可以完整执行所需的 Space 级语义配置。
 *
 * <p>Q0 是不可关闭的系统基线，因此本对象只为首轮的可选术语增强提供开关。
 * 本配置只描述查询优化、召回、融合、精排和覆盖判断，不包含索引代际、Embedding
 * 维度或其他需要重新建索引的参数。</p>
 *
 * @param firstRound 首轮 Q0 与术语增强配置
 * @param branches 物理召回分支、候选数量和 RRF 配置
 * @param reranker 固定精排节点配置
 * @param coverage Evidence Coverage 判断配置
 * @param maximumRetrievalAttempts 单个请求最多实际执行的检索计划数
 * @param chainNodeEnables 证据不足后固定 Chain 中每个节点的开关
 * @param crossSpace 跨 Space 顺序重试配置
 */
public record RetrievalConfiguration(
        FirstRound firstRound,
        Branches branches,
        Reranker reranker,
        Coverage coverage,
        int maximumRetrievalAttempts,
        Map<ChainNode, Boolean> chainNodeEnables,
        CrossSpace crossSpace
) {
    private static final int ABSOLUTE_MAXIMUM_ATTEMPTS = 16;

    /**
     * 返回创建 Space 时需要立即物化的确定性基线配置。
     *
     * <p>该默认值不调用模型、不跨 Space，也不执行反馈 Chain；Q0 仍会使用已安装且在
     * 配置中启用的召回通道。它只用于新 Space 初始修订，后续系统默认变化不会静默改变
     * 已有 Space。</p>
     */
    public static RetrievalConfiguration deterministicBaseline() {
        EnumMap<RetrievalChannel, Branch> channels = new EnumMap<>(RetrievalChannel.class);
        channels.put(RetrievalChannel.KEYWORD, new Branch(true, 40, 1.0D));
        channels.put(RetrievalChannel.VECTOR, new Branch(true, 40, 1.0D));
        channels.put(RetrievalChannel.GRAPH, new Branch(true, 20, 0.8D));
        channels.put(RetrievalChannel.PAGE, new Branch(true, 20, 0.8D));
        EnumMap<ChainNode, Boolean> chainNodes = new EnumMap<>(ChainNode.class);
        for (ChainNode node : ChainNode.values()) {
            chainNodes.put(node, false);
        }
        return new RetrievalConfiguration(
                new FirstRound(false, "none", 0),
                new Branches(1, 4, 60, channels),
                new Reranker(false, "deterministic", "rrf-order", 40, 8),
                new Coverage(false, "deterministic", "none", "none", 20, 0.75D),
                1,
                chainNodes,
                new CrossSpace(false, 1)
        );
    }

    /** 校验完整配置的跨字段不变量。 */
    public RetrievalConfiguration {
        Objects.requireNonNull(firstRound, "firstRound must not be null");
        Objects.requireNonNull(branches, "branches must not be null");
        Objects.requireNonNull(reranker, "reranker must not be null");
        Objects.requireNonNull(coverage, "coverage must not be null");
        if (maximumRetrievalAttempts < 1
                || maximumRetrievalAttempts > ABSOLUTE_MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maximumRetrievalAttempts must be between 1 and 16"
            );
        }
        chainNodeEnables = immutableChainNodeEnables(chainNodeEnables);
        Objects.requireNonNull(crossSpace, "crossSpace must not be null");
        boolean chainEnabled = chainNodeEnables.values().stream().anyMatch(Boolean::booleanValue);
        if (firstRound.termExpansionEnabled()
                && branches.maximumVariantsPerAttempt() < 2) {
            throw new IllegalArgumentException(
                    "enabled term expansion requires capacity for Q0 and one generated variant"
            );
        }
        if (chainEnabled && !coverage.enabled()) {
            throw new IllegalArgumentException(
                    "enabled Chain nodes require Coverage to be enabled"
            );
        }
        if (chainEnabled && maximumRetrievalAttempts < 2) {
            throw new IllegalArgumentException(
                    "enabled Chain nodes require at least two retrieval attempts"
            );
        }
        if (crossSpace.enabled() != chainNodeEnables.get(ChainNode.NEXT_SPACE)) {
            throw new IllegalArgumentException(
                    "crossSpace enabled state must match the NEXT_SPACE node switch"
            );
        }
    }

    /**
     * 计算只由有效检索语义决定的稳定 SHA-256。
     *
     * <p>租户、Space、修订号和审计时间不参与计算；相同语义配置在不同 Space
     * 中得到相同指纹，便于评测结果按真实执行配置聚合。</p>
     *
     * @return 小写十六进制 SHA-256
     */
    public String fingerprint() {
        StringBuilder material = new StringBuilder(512);
        append(material, "termExpansionEnabled", firstRound.termExpansionEnabled());
        append(material, "terminologyResourceId", firstRound.terminologyResourceId());
        append(material, "maximumExpansionTerms", firstRound.maximumExpansionTerms());
        append(material, "maximumVariantsPerAttempt", branches.maximumVariantsPerAttempt());
        append(material, "maximumRetrievalBranches", branches.maximumRetrievalBranches());
        append(material, "rrfConstant", branches.rrfConstant());
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            Branch branch = branches.channels().get(channel);
            append(material, "channel", channel.name());
            append(material, "enabled", branch.enabled());
            append(material, "topK", branch.topK());
            append(material, "rrfWeight", Double.toHexString(branch.rrfWeight()));
        }
        append(material, "rerankerEnabled", reranker.enabled());
        append(material, "rerankerProvider", reranker.providerId());
        append(material, "rerankerModel", reranker.modelId());
        append(material, "rerankCandidateLimit", reranker.candidateLimit());
        append(material, "rerankOutputTopK", reranker.outputTopK());
        append(material, "coverageEnabled", coverage.enabled());
        append(material, "coverageProvider", coverage.providerId());
        append(material, "coverageModel", coverage.modelId());
        append(material, "coveragePromptVersion", coverage.promptVersion());
        append(material, "coverageMemoryLimit", coverage.memoryLimit());
        append(
                material,
                "coverageSufficiencyThreshold",
                Double.toHexString(coverage.sufficiencyThreshold())
        );
        append(material, "maximumRetrievalAttempts", maximumRetrievalAttempts);
        for (ChainNode node : ChainNode.values()) {
            append(material, "chainNode", node.name());
            append(material, "chainNodeEnabled", chainNodeEnables.get(node));
        }
        append(material, "crossSpaceEnabled", crossSpace.enabled());
        append(material, "maximumCrossSpaces", crossSpace.maximumSpaces());
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            material.toString().getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private static Map<ChainNode, Boolean> immutableChainNodeEnables(
            Map<ChainNode, Boolean> values
    ) {
        Objects.requireNonNull(values, "chainNodeEnables must not be null");
        if (values.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException(
                    "chainNodeEnables must not contain null keys or values"
            );
        }
        EnumMap<ChainNode, Boolean> materialized = new EnumMap<>(ChainNode.class);
        materialized.putAll(values);
        if (materialized.size() != ChainNode.values().length) {
            throw new IllegalArgumentException(
                    "chainNodeEnables must materialize every ChainNode"
            );
        }
        for (ChainNode node : ChainNode.values()) {
            Objects.requireNonNull(
                    materialized.get(node),
                    "chain node switch must not be null"
            );
        }
        return Map.copyOf(materialized);
    }

    private static void append(StringBuilder target, String name, Object value) {
        String text = String.valueOf(value);
        target.append(name.length()).append(':').append(name)
                .append('=').append(text.length()).append(':').append(text).append('\n');
    }

    /**
     * 首轮配置；Q0 始终执行，只有术语增强可以按 Space 关闭。
     *
     * @param termExpansionEnabled 是否在 Q0 之外尝试生成一个术语增强 Variant
     * @param terminologyResourceId 当前 Space 使用的受控术语资源标识
     * @param maximumExpansionTerms 单次查询最多采用的扩展术语数
     */
    public record FirstRound(
            boolean termExpansionEnabled,
            String terminologyResourceId,
            int maximumExpansionTerms
    ) {
        /** 规范化资源标识并限制在线查询放大。 */
        public FirstRound {
            terminologyResourceId = stableId(
                    terminologyResourceId,
                    "terminologyResourceId",
                    128
            );
            if (maximumExpansionTerms < 0 || maximumExpansionTerms > 64) {
                throw new IllegalArgumentException(
                        "maximumExpansionTerms must be between 0 and 64"
                );
            }
            if (termExpansionEnabled && maximumExpansionTerms < 1) {
                throw new IllegalArgumentException(
                        "enabled term expansion requires at least one expansion term"
                );
            }
        }
    }

    /**
     * 保存一次 Retrieval Plan 的静态分支边界和 Weighted RRF 参数。
     *
     * @param maximumVariantsPerAttempt 单次尝试最多包含的 Query Variant 数量
     * @param maximumRetrievalBranches 单次尝试最多执行的物理分支数量
     * @param rrfConstant 所有分支共同使用的 RRF 平滑常数
     * @param channels 每个已知召回通道的完整配置
     */
    public record Branches(
            int maximumVariantsPerAttempt,
            int maximumRetrievalBranches,
            int rrfConstant,
            Map<RetrievalChannel, Branch> channels
    ) {
        /** 校验分支预算，并要求每个领域通道都有物化值。 */
        public Branches {
            if (maximumVariantsPerAttempt < 1 || maximumVariantsPerAttempt > 16) {
                throw new IllegalArgumentException(
                        "maximumVariantsPerAttempt must be between 1 and 16"
                );
            }
            if (maximumRetrievalBranches < 1 || maximumRetrievalBranches > 64) {
                throw new IllegalArgumentException(
                        "maximumRetrievalBranches must be between 1 and 64"
                );
            }
            if (maximumRetrievalBranches < maximumVariantsPerAttempt) {
                throw new IllegalArgumentException(
                        "maximumRetrievalBranches must allow at least one branch per variant"
                );
            }
            if (rrfConstant < 1 || rrfConstant > 10_000) {
                throw new IllegalArgumentException("rrfConstant must be between 1 and 10000");
            }
            Objects.requireNonNull(channels, "channels must not be null");
            if (channels.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getValue() == null)) {
                throw new IllegalArgumentException(
                        "channels must not contain null keys or values"
                );
            }
            EnumMap<RetrievalChannel, Branch> materialized =
                    new EnumMap<>(RetrievalChannel.class);
            materialized.putAll(channels);
            if (materialized.size() != RetrievalChannel.values().length) {
                throw new IllegalArgumentException(
                        "channels must materialize every RetrievalChannel"
                );
            }
            for (RetrievalChannel channel : RetrievalChannel.values()) {
                Objects.requireNonNull(
                        materialized.get(channel),
                        "channel configuration must not be null"
                );
            }
            if (materialized.values().stream().noneMatch(Branch::enabled)) {
                throw new IllegalArgumentException("at least one retrieval branch must be enabled");
            }
            channels = Map.copyOf(materialized);
        }
    }

    /**
     * 定义一个物理召回通道的候选深度和 RRF 权重。
     *
     * @param enabled 当前 Space 是否允许规划该通道
     * @param topK 通道每次物理查询最多返回的候选数
     * @param rrfWeight 该通道的 Weighted RRF 权重；关闭通道时保留该值但不参与融合
     */
    public record Branch(boolean enabled, int topK, double rrfWeight) {
        /** 校验单通道绝对安全边界。 */
        public Branch {
            if (topK < 1 || topK > 1_000) {
                throw new IllegalArgumentException("branch topK must be between 1 and 1000");
            }
            if (!Double.isFinite(rrfWeight) || rrfWeight < 0.0D || rrfWeight > 100.0D) {
                throw new IllegalArgumentException(
                        "rrfWeight must be finite and between 0 and 100"
                );
            }
            if (enabled && rrfWeight == 0.0D) {
                throw new IllegalArgumentException(
                        "enabled retrieval branch must have a positive rrfWeight"
                );
            }
        }
    }

    /**
     * 固定精排节点配置。
     *
     * @param enabled 是否调用精排实现；关闭时按 RRF 顺序透传
     * @param providerId 不含端点和凭据的 Provider 标识
     * @param modelId 模型或确定性实现标识
     * @param candidateLimit 送入精排器的 RRF 候选上限
     * @param outputTopK 精排输出候选上限
     */
    public record Reranker(
            boolean enabled,
            String providerId,
            String modelId,
            int candidateLimit,
            int outputTopK
    ) {
        /** 校验精排窗口和输出窗口关系。 */
        public Reranker {
            providerId = stableId(providerId, "reranker providerId", 64);
            modelId = stableId(modelId, "reranker modelId", 128);
            if (candidateLimit < 1 || candidateLimit > 1_000) {
                throw new IllegalArgumentException(
                        "reranker candidateLimit must be between 1 and 1000"
                );
            }
            if (outputTopK < 1 || outputTopK > candidateLimit) {
                throw new IllegalArgumentException(
                        "reranker outputTopK must be positive and not exceed candidateLimit"
                );
            }
        }
    }

    /**
     * Evidence Coverage 判断和有限记忆配置。
     *
     * @param enabled 是否执行 Coverage 判断；关闭时终态为未评测
     * @param providerId 不含端点和凭据的 Judge Provider 标识
     * @param modelId Judge 模型标识
     * @param promptVersion 固定 Prompt 版本
     * @param memoryLimit 跨轮保留的 Candidate 数量上限
     * @param sufficiencyThreshold 程序用于提前结束的充分性阈值
     */
    public record Coverage(
            boolean enabled,
            String providerId,
            String modelId,
            String promptVersion,
            int memoryLimit,
            double sufficiencyThreshold
    ) {
        /** 校验 Judge 合同标识和有限记忆边界。 */
        public Coverage {
            providerId = stableId(providerId, "coverage providerId", 64);
            modelId = stableId(modelId, "coverage modelId", 128);
            promptVersion = stableId(promptVersion, "coverage promptVersion", 128);
            if (memoryLimit < 1 || memoryLimit > 100) {
                throw new IllegalArgumentException(
                        "coverage memoryLimit must be between 1 and 100"
                );
            }
            sufficiencyThreshold = DomainChecks.unitScore(
                    sufficiencyThreshold,
                    "coverage sufficiencyThreshold"
            );
        }
    }

    /**
     * 跨 Space 顺序重试配置。
     *
     * @param enabled 是否允许执行固定链末尾的 NEXT_SPACE
     * @param maximumSpaces 单请求最多实际进入的 Space 数量，包含起始 Space
     */
    public record CrossSpace(boolean enabled, int maximumSpaces) {
        /** 限制顺序路由的绝对放大倍数。 */
        public CrossSpace {
            if (maximumSpaces < 1 || maximumSpaces > 16) {
                throw new IllegalArgumentException(
                        "crossSpace maximumSpaces must be between 1 and 16"
                );
            }
            if (enabled && maximumSpaces < 2) {
                throw new IllegalArgumentException(
                        "enabled crossSpace requires maximumSpaces of at least 2"
                );
            }
        }
    }

    /**
     * 证据不足后可按固定前置条件执行的有限节点集合。
     *
     * <p>枚举顺序是权威执行顺序，Space 只能开关节点，不能重排编排。</p>
     */
    public enum ChainNode {
        GAP_QUERY,
        PRF,
        RELAX_CONSTRAINTS,
        NARROW_CONSTRAINTS,
        STEP_BACK,
        HYDE,
        NEXT_SPACE
    }

    private static String stableId(String value, String field, int maximumLength) {
        String normalized = DomainChecks.requiredText(value, field, maximumLength);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }
}
