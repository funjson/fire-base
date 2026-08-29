package dev.infinityknowledge.domain.retrieval.configuration;

import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Branch;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Branches;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Coverage;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.CrossSpace;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.FirstRound;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Reranker;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 使用 Space 当前修订和请求级强类型覆盖解析最终检索配置。
 *
 * <p>Resolver 是纯 Java 组件，不读取 Spring 配置或数据库。调用方必须显式提供
 * 系统硬上限；任何越界都直接失败，避免裁剪后 Trace 中的配置与请求不一致。</p>
 */
public final class RetrievalConfigurationResolver {

    /**
     * 合并请求覆盖并生成带来源修订和最终指纹的有效配置。
     *
     * @param source Space 当前不可变配置修订
     * @param override 请求级覆盖；传 {@code null} 等同空覆盖
     * @param hardLimits 部署级硬上限
     * @return 最终有效配置
     */
    public EffectiveRetrievalConfiguration resolve(
            SpaceRetrievalConfiguration source,
            RetrievalConfigurationOverride override,
            RetrievalConfigurationHardLimits hardLimits
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(hardLimits, "hardLimits must not be null");
        RetrievalConfigurationOverride requested = override == null
                ? RetrievalConfigurationOverride.empty()
                : override;
        validate(source.configuration(), hardLimits);
        RetrievalConfiguration effective = merge(source.configuration(), requested);
        validate(effective, hardLimits);
        return EffectiveRetrievalConfiguration.from(source, effective);
    }

    /**
     * 在保存 Space 新修订或执行请求前校验完整配置没有突破部署级硬上限。
     *
     * @param configuration 待校验的完整配置
     * @param hardLimits 部署级硬上限
     */
    public void validate(
            RetrievalConfiguration configuration,
            RetrievalConfigurationHardLimits hardLimits
    ) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(hardLimits, "hardLimits must not be null");
        validateHardLimits(configuration, hardLimits);
    }

    private RetrievalConfiguration merge(
            RetrievalConfiguration base,
            RetrievalConfigurationOverride override
    ) {
        var firstOverride = override.firstRound();
        FirstRound firstRound = firstOverride == null
                ? base.firstRound()
                : new FirstRound(
                        value(firstOverride.termExpansionEnabled(),
                                base.firstRound().termExpansionEnabled()),
                        value(firstOverride.terminologyResourceId(),
                                base.firstRound().terminologyResourceId()),
                        value(firstOverride.maximumExpansionTerms(),
                                base.firstRound().maximumExpansionTerms())
                );
        Branches branches = mergeBranches(base.branches(), override.branches());
        Reranker reranker = mergeReranker(base.reranker(), override.reranker());
        Coverage coverage = mergeCoverage(base.coverage(), override.coverage());
        CrossSpace crossSpace = mergeCrossSpace(base.crossSpace(), override.crossSpace());
        return new RetrievalConfiguration(
                firstRound,
                branches,
                reranker,
                coverage,
                value(override.maximumRetrievalAttempts(), base.maximumRetrievalAttempts()),
                mergeChainNodeEnables(base.chainNodeEnables(), override.chainNodeEnables()),
                crossSpace
        );
    }

    private Map<RetrievalConfiguration.ChainNode, Boolean> mergeChainNodeEnables(
            Map<RetrievalConfiguration.ChainNode, Boolean> base,
            Map<RetrievalConfiguration.ChainNode, Boolean> override
    ) {
        if (override == null) {
            return base;
        }
        EnumMap<RetrievalConfiguration.ChainNode, Boolean> merged =
                new EnumMap<>(base);
        merged.putAll(override);
        return merged;
    }

    private Branches mergeBranches(
            Branches base,
            RetrievalConfigurationOverride.BranchesOverride override
    ) {
        if (override == null) {
            return base;
        }
        EnumMap<RetrievalChannel, Branch> channels = new EnumMap<>(base.channels());
        if (override.channels() != null) {
            override.channels().forEach((channel, requested) -> {
                Branch current = channels.get(channel);
                channels.put(channel, new Branch(
                        value(requested.enabled(), current.enabled()),
                        value(requested.topK(), current.topK()),
                        value(requested.rrfWeight(), current.rrfWeight())
                ));
            });
        }
        return new Branches(
                value(override.maximumVariantsPerAttempt(),
                        base.maximumVariantsPerAttempt()),
                value(override.maximumRetrievalBranches(),
                        base.maximumRetrievalBranches()),
                value(override.rrfConstant(), base.rrfConstant()),
                channels
        );
    }

    private Reranker mergeReranker(
            Reranker base,
            RetrievalConfigurationOverride.RerankerOverride override
    ) {
        if (override == null) {
            return base;
        }
        return new Reranker(
                value(override.enabled(), base.enabled()),
                value(override.providerId(), base.providerId()),
                value(override.modelId(), base.modelId()),
                value(override.candidateLimit(), base.candidateLimit()),
                value(override.outputTopK(), base.outputTopK())
        );
    }

    private Coverage mergeCoverage(
            Coverage base,
            RetrievalConfigurationOverride.CoverageOverride override
    ) {
        if (override == null) {
            return base;
        }
        return new Coverage(
                value(override.enabled(), base.enabled()),
                value(override.providerId(), base.providerId()),
                value(override.modelId(), base.modelId()),
                value(override.promptVersion(), base.promptVersion()),
                value(override.memoryLimit(), base.memoryLimit()),
                value(override.sufficiencyThreshold(), base.sufficiencyThreshold())
        );
    }

    private CrossSpace mergeCrossSpace(
            CrossSpace base,
            RetrievalConfigurationOverride.CrossSpaceOverride override
    ) {
        if (override == null) {
            return base;
        }
        return new CrossSpace(
                value(override.enabled(), base.enabled()),
                value(override.maximumSpaces(), base.maximumSpaces())
        );
    }

    /** 对完整配置的所有值执行系统上限校验，包括当前关闭的能力。 */
    private void validateHardLimits(
            RetrievalConfiguration configuration,
            RetrievalConfigurationHardLimits limits
    ) {
        upper(
                configuration.firstRound().maximumExpansionTerms(),
                limits.maximumExpansionTerms(),
                "maximumExpansionTerms"
        );
        upper(
                configuration.branches().maximumVariantsPerAttempt(),
                limits.maximumVariantsPerAttempt(),
                "maximumVariantsPerAttempt"
        );
        upper(
                configuration.branches().maximumRetrievalBranches(),
                limits.maximumRetrievalBranches(),
                "maximumRetrievalBranches"
        );
        upper(
                configuration.branches().rrfConstant(),
                limits.maximumRrfConstant(),
                "rrfConstant"
        );
        for (Map.Entry<RetrievalChannel, Branch> entry
                : configuration.branches().channels().entrySet()) {
            upper(
                    entry.getValue().topK(),
                    limits.maximumBranchTopK(),
                    "branch topK for " + entry.getKey().name()
            );
            if (entry.getValue().rrfWeight() > limits.maximumRrfWeight()) {
                throw new RetrievalConfigurationLimitExceededException(
                        "rrfWeight for " + entry.getKey().name()
                                + " exceeds system hard limit " + limits.maximumRrfWeight()
                );
            }
        }
        upper(
                configuration.reranker().candidateLimit(),
                limits.maximumRerankCandidates(),
                "reranker candidateLimit"
        );
        upper(
                configuration.reranker().outputTopK(),
                limits.maximumRerankOutputTopK(),
                "reranker outputTopK"
        );
        upper(
                configuration.coverage().memoryLimit(),
                limits.maximumCoverageMemory(),
                "coverage memoryLimit"
        );
        upper(
                configuration.maximumRetrievalAttempts(),
                limits.maximumRetrievalAttempts(),
                "maximumRetrievalAttempts"
        );
        int enabledChainNodes = (int) configuration.chainNodeEnables().values().stream()
                .filter(Boolean::booleanValue)
                .count();
        upper(enabledChainNodes, limits.maximumChainNodes(), "enabled chain node count");
        upper(
                configuration.crossSpace().maximumSpaces(),
                limits.maximumCrossSpaces(),
                "crossSpace maximumSpaces"
        );
    }

    private static void upper(int value, int maximum, String name) {
        if (value > maximum) {
            throw new RetrievalConfigurationLimitExceededException(
                    name + " exceeds system hard limit " + maximum
            );
        }
    }

    private static boolean value(Boolean requested, boolean fallback) {
        return requested == null ? fallback : requested;
    }

    private static int value(Integer requested, int fallback) {
        return requested == null ? fallback : requested;
    }

    private static double value(Double requested, double fallback) {
        return requested == null ? fallback : requested;
    }

    private static String value(String requested, String fallback) {
        return requested == null ? fallback : requested;
    }
}
