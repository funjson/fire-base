package dev.infinityknowledge.domain.retrieval.configuration;

/**
 * 表示部署级不可由 Space 或请求覆盖的检索硬上限。
 *
 * <p>这些值只限制资源放大，不提供业务默认值。Resolver 对 Space 配置和请求覆盖后的
 * 最终配置都执行拒绝式校验，不会静默裁剪并生成难以复现的结果。</p>
 *
 * @param maximumExpansionTerms 最大扩展术语数
 * @param maximumVariantsPerAttempt 单次尝试最大 Variant 数
 * @param maximumRetrievalBranches 单次尝试最大物理分支数
 * @param maximumBranchTopK 单个物理分支最大候选数
 * @param maximumRrfConstant 最大 RRF 平滑常数
 * @param maximumRrfWeight 单分支最大 RRF 权重
 * @param maximumRerankCandidates 最大精排候选窗口
 * @param maximumRerankOutputTopK 最大精排输出窗口
 * @param maximumCoverageMemory 最大 Coverage Memory
 * @param maximumRetrievalAttempts 单请求最大检索尝试次数
 * @param maximumChainNodes 最大固定 Chain 节点数
 * @param maximumCrossSpaces 单请求最多进入的 Space 数量
 */
public record RetrievalConfigurationHardLimits(
        int maximumExpansionTerms,
        int maximumVariantsPerAttempt,
        int maximumRetrievalBranches,
        int maximumBranchTopK,
        int maximumRrfConstant,
        double maximumRrfWeight,
        int maximumRerankCandidates,
        int maximumRerankOutputTopK,
        int maximumCoverageMemory,
        int maximumRetrievalAttempts,
        int maximumChainNodes,
        int maximumCrossSpaces
) {
    /** 校验系统硬上限本身不突破领域绝对安全边界。 */
    public RetrievalConfigurationHardLimits {
        range(maximumExpansionTerms, 0, 64, "maximumExpansionTerms");
        range(maximumVariantsPerAttempt, 1, 16, "maximumVariantsPerAttempt");
        range(maximumRetrievalBranches, 1, 64, "maximumRetrievalBranches");
        range(maximumBranchTopK, 1, 1_000, "maximumBranchTopK");
        range(maximumRrfConstant, 1, 10_000, "maximumRrfConstant");
        if (!Double.isFinite(maximumRrfWeight)
                || maximumRrfWeight <= 0.0D
                || maximumRrfWeight > 100.0D) {
            throw new IllegalArgumentException(
                    "maximumRrfWeight must be finite, positive and at most 100"
            );
        }
        range(maximumRerankCandidates, 1, 1_000, "maximumRerankCandidates");
        range(maximumRerankOutputTopK, 1, 1_000, "maximumRerankOutputTopK");
        if (maximumRerankOutputTopK > maximumRerankCandidates) {
            throw new IllegalArgumentException(
                    "maximumRerankOutputTopK must not exceed maximumRerankCandidates"
            );
        }
        range(maximumCoverageMemory, 1, 100, "maximumCoverageMemory");
        range(maximumRetrievalAttempts, 1, 16, "maximumRetrievalAttempts");
        range(
                maximumChainNodes,
                0,
                RetrievalConfiguration.ChainNode.values().length,
                "maximumChainNodes"
        );
        range(maximumCrossSpaces, 1, 16, "maximumCrossSpaces");
    }

    /**
     * 返回适合首轮实现的保守系统上限；生产部署仍可通过外部配置提供更严格值。
     */
    public static RetrievalConfigurationHardLimits conservativeDefaults() {
        return new RetrievalConfigurationHardLimits(
                16,
                4,
                8,
                200,
                200,
                10.0D,
                200,
                100,
                50,
                8,
                RetrievalConfiguration.ChainNode.values().length,
                4
        );
    }

    private static void range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
            );
        }
    }
}
