package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定可选精排器类型、阶段超时和厂商无关的输入预算。
 *
 * @param enabled 是否启用真实精排器
 * @param provider 精排实现类型
 * @param stageTimeout 单次精排阶段硬超时
 * @param maxCandidates 单次最多参与精排的候选数
 * @param maxQueryCharacters 单次最多发送的查询字符数
 * @param maxCandidateCharacters 单个候选最多发送的字符数
 * @param maxTotalCharacters 单次精排请求的总字符预算
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.reranker")
public record RerankerProperties(
        boolean enabled,
        Provider provider,
        Duration stageTimeout,
        int maxCandidates,
        int maxQueryCharacters,
        int maxCandidateCharacters,
        int maxTotalCharacters
) {

    /**
     * 应用保守默认值并拒绝无界资源配置。
     */
    public RerankerProperties {
        provider = provider == null ? Provider.EMBEDDING : provider;
        stageTimeout = stageTimeout == null ? Duration.ofSeconds(4) : stageTimeout;
        if (stageTimeout.isZero()
                || stageTimeout.isNegative()
                || stageTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "reranker stageTimeout must be positive and at most one minute"
            );
        }
        if (maxCandidates < 1) {
            maxCandidates = 24;
        }
        if (maxQueryCharacters < 1) {
            maxQueryCharacters = 4_096;
        }
        if (maxCandidateCharacters < 1) {
            maxCandidateCharacters = 4_096;
        }
        if (maxTotalCharacters < 1) {
            maxTotalCharacters = 100_000;
        }
        if (maxCandidates > 256) {
            throw new IllegalArgumentException(
                    "reranker maxCandidates must not exceed 256"
            );
        }
        if (maxQueryCharacters > 32_768) {
            throw new IllegalArgumentException(
                    "reranker maxQueryCharacters must not exceed 32768"
            );
        }
        if (maxCandidateCharacters > 100_000) {
            throw new IllegalArgumentException(
                    "reranker maxCandidateCharacters must not exceed 100000"
            );
        }
        if (maxTotalCharacters <= maxQueryCharacters
                || maxTotalCharacters > 1_000_000) {
            throw new IllegalArgumentException(
                    "reranker maxTotalCharacters must exceed maxQueryCharacters "
                            + "and be at most 1000000"
            );
        }
    }

    /**
     * 定义精排器的具体实现，保持 Gateway 只依赖统一 Reranker SPI。
     */
    public enum Provider {
        EMBEDDING,
        ZHIPU
    }
}
