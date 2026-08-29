package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 控制台和管理 API 使用的完整检索配置，不暴露持久化 JSON 或领域序列化细节。
 */
public record RetrievalConfigurationDto(
        FirstRound firstRound,
        Branches branches,
        Reranker reranker,
        Coverage coverage,
        int maximumRetrievalAttempts,
        Map<String, Boolean> chainNodeEnables,
        CrossSpace crossSpace
) {

    /** 把 API 配置转换为经过完整不变量校验的领域配置。 */
    public RetrievalConfiguration toDomain() {
        Objects.requireNonNull(firstRound, "firstRound must not be null");
        Objects.requireNonNull(branches, "branches must not be null");
        Objects.requireNonNull(reranker, "reranker must not be null");
        Objects.requireNonNull(coverage, "coverage must not be null");
        Objects.requireNonNull(crossSpace, "crossSpace must not be null");
        return new RetrievalConfiguration(
                new RetrievalConfiguration.FirstRound(
                        firstRound.termExpansionEnabled(),
                        firstRound.terminologyResourceId(),
                        firstRound.maximumExpansionTerms()
                ),
                branches.toDomain(),
                new RetrievalConfiguration.Reranker(
                        reranker.enabled(),
                        reranker.providerId(),
                        reranker.modelId(),
                        reranker.candidateLimit(),
                        reranker.outputTopK()
                ),
                new RetrievalConfiguration.Coverage(
                        coverage.enabled(),
                        coverage.providerId(),
                        coverage.modelId(),
                        coverage.promptVersion(),
                        coverage.memoryLimit(),
                        coverage.sufficiencyThreshold()
                ),
                maximumRetrievalAttempts,
                chainNodeMap(chainNodeEnables, true),
                new RetrievalConfiguration.CrossSpace(
                        crossSpace.enabled(),
                        crossSpace.maximumSpaces()
                )
        );
    }

    /** 将领域配置转换为稳定 API 结构。 */
    public static RetrievalConfigurationDto from(RetrievalConfiguration value) {
        Objects.requireNonNull(value, "value must not be null");
        Map<String, Branch> channels = new LinkedHashMap<>();
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            RetrievalConfiguration.Branch branch = value.branches().channels().get(channel);
            channels.put(channel.name(), new Branch(
                    branch.enabled(), branch.topK(), branch.rrfWeight()
            ));
        }
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        for (RetrievalConfiguration.ChainNode node
                : RetrievalConfiguration.ChainNode.values()) {
            nodes.put(node.name(), value.chainNodeEnables().get(node));
        }
        return new RetrievalConfigurationDto(
                new FirstRound(
                        value.firstRound().termExpansionEnabled(),
                        value.firstRound().terminologyResourceId(),
                        value.firstRound().maximumExpansionTerms()
                ),
                new Branches(
                        value.branches().maximumVariantsPerAttempt(),
                        value.branches().maximumRetrievalBranches(),
                        value.branches().rrfConstant(),
                        channels
                ),
                new Reranker(
                        value.reranker().enabled(),
                        value.reranker().providerId(),
                        value.reranker().modelId(),
                        value.reranker().candidateLimit(),
                        value.reranker().outputTopK()
                ),
                new Coverage(
                        value.coverage().enabled(),
                        value.coverage().providerId(),
                        value.coverage().modelId(),
                        value.coverage().promptVersion(),
                        value.coverage().memoryLimit(),
                        value.coverage().sufficiencyThreshold()
                ),
                value.maximumRetrievalAttempts(),
                nodes,
                new CrossSpace(
                        value.crossSpace().enabled(),
                        value.crossSpace().maximumSpaces()
                )
        );
    }

    static Map<RetrievalConfiguration.ChainNode, Boolean> chainNodeMap(
            Map<String, Boolean> values,
            boolean requireAll
    ) {
        Objects.requireNonNull(values, "chainNodeEnables must not be null");
        EnumMap<RetrievalConfiguration.ChainNode, Boolean> mapped =
                new EnumMap<>(RetrievalConfiguration.ChainNode.class);
        values.forEach((key, enabled) -> {
            if (key == null || enabled == null) {
                throw new IllegalArgumentException(
                        "chainNodeEnables must not contain null keys or values"
                );
            }
            mapped.put(chainNode(key), enabled);
        });
        if (requireAll
                && mapped.size() != RetrievalConfiguration.ChainNode.values().length) {
            throw new IllegalArgumentException(
                    "complete configuration must contain every chain node switch"
            );
        }
        return Map.copyOf(mapped);
    }

    static RetrievalConfiguration.ChainNode chainNode(String value) {
        try {
            return RetrievalConfiguration.ChainNode.valueOf(value.strip().toUpperCase(
                    java.util.Locale.ROOT
            ));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("unsupported retrieval chain node", invalid);
        }
    }

    static RetrievalChannel channel(String value) {
        try {
            return RetrievalChannel.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("unsupported retrieval channel", invalid);
        }
    }

    public record FirstRound(
            boolean termExpansionEnabled,
            String terminologyResourceId,
            int maximumExpansionTerms
    ) {
    }

    public record Branches(
            int maximumVariantsPerAttempt,
            int maximumRetrievalBranches,
            int rrfConstant,
            Map<String, Branch> channels
    ) {
        RetrievalConfiguration.Branches toDomain() {
            Objects.requireNonNull(channels, "channels must not be null");
            EnumMap<RetrievalChannel, RetrievalConfiguration.Branch> mapped =
                    new EnumMap<>(RetrievalChannel.class);
            channels.forEach((key, value) -> {
                Objects.requireNonNull(value, "branch must not be null");
                mapped.put(channel(key), new RetrievalConfiguration.Branch(
                        value.enabled(), value.topK(), value.rrfWeight()
                ));
            });
            return new RetrievalConfiguration.Branches(
                    maximumVariantsPerAttempt,
                    maximumRetrievalBranches,
                    rrfConstant,
                    mapped
            );
        }
    }

    public record Branch(boolean enabled, int topK, double rrfWeight) {
    }

    public record Reranker(
            boolean enabled,
            String providerId,
            String modelId,
            int candidateLimit,
            int outputTopK
    ) {
    }

    public record Coverage(
            boolean enabled,
            String providerId,
            String modelId,
            String promptVersion,
            int memoryLimit,
            double sufficiencyThreshold
    ) {
    }

    public record CrossSpace(boolean enabled, int maximumSpaces) {
    }
}
