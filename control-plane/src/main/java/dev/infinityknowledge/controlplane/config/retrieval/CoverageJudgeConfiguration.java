package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.controlplane.config.model.ModelStageBudgetValidator;
import dev.infinityknowledge.controlplane.config.model.ModelTokenEstimatorResolver;
import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuCoverageJudge;
import dev.infinityknowledge.provider.zhipu.ZhipuGenerationConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuJsonGenerationClient;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/** 在显式启用时装配有界的智谱 Coverage Judge。 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval.coverage-judge",
        name = "enabled",
        havingValue = "true"
)
public class CoverageJudgeConfiguration {

    /** 创建不接收阈值和排序分数的 Coverage Judge。 */
    @Bean
    @ConditionalOnMissingBean(CoverageJudge.class)
    CoverageJudge coverageJudge(
            CoverageJudgeProperties properties,
            ObjectProvider<ModelTokenEstimator> tokenEstimatorProvider
    ) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "coverage judge apiKey is required when the judge is enabled"
            );
        }
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout());
        if (!properties.proxyHost().isEmpty()) {
            http.proxy(ProxySelector.of(new InetSocketAddress(
                    properties.proxyHost(),
                    properties.proxyPort()
            )));
        }
        JsonMapper mapper = JsonMapper.builder().build();
        ZhipuGenerationConfig generation = new ZhipuGenerationConfig(
                properties.endpoint(),
                properties.apiKey(),
                properties.model(),
                properties.requestTimeout(),
                properties.maxAttempts(),
                properties.initialBackoff(),
                properties.maxInputCharacters(),
                properties.maximumPromptTokens(),
                properties.maxOutputTokens()
        );
        ModelTokenEstimator tokenEstimator = ModelTokenEstimatorResolver.require(
                tokenEstimatorProvider,
                "zhipu",
                properties.model(),
                "coverage judge"
        );
        ModelStageBudgetValidator.requireFits(
                properties.stageTimeout(),
                tokenEstimator.maximumLatency(),
                generation.maximumLatency(),
                "coverage judge"
        );
        ZhipuJsonGenerationClient client = new ZhipuJsonGenerationClient(
                generation,
                http.build(),
                mapper,
                new ThreadRetrySleeper(),
                tokenEstimator
        );
        return new ZhipuCoverageJudge(
                client,
                mapper
        );
    }
}
