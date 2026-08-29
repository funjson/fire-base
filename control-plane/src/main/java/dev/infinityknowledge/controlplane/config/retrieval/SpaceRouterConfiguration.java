package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.controlplane.config.model.ModelStageBudgetValidator;
import dev.infinityknowledge.controlplane.config.model.ModelTokenEstimatorResolver;
import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuGenerationConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuJsonGenerationClient;
import dev.infinityknowledge.provider.zhipu.ZhipuSpaceRouter;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/** 在显式启用时装配有界的智谱空间排序模型。 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval.space-router",
        name = "enabled",
        havingValue = "true"
)
public class SpaceRouterConfiguration {

    /**
     * 创建只能重排服务端候选列表的 Space Router。
     *
     * @param properties 模型和网络边界
     * @return Space Router
     */
    @Bean
    @ConditionalOnMissingBean(SpaceRouter.class)
    SpaceRouter spaceRouter(
            SpaceRouterProperties properties,
            ObjectProvider<ModelTokenEstimator> tokenEstimatorProvider
    ) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "space router apiKey is required when model routing is enabled"
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
                "space router"
        );
        ModelStageBudgetValidator.requireFits(
                properties.stageTimeout(),
                tokenEstimator.maximumLatency(),
                generation.maximumLatency(),
                "space router"
        );
        ZhipuJsonGenerationClient client = new ZhipuJsonGenerationClient(
                generation,
                http.build(),
                mapper,
                new ThreadRetrySleeper(),
                tokenEstimator
        );
        return new ZhipuSpaceRouter(
                client,
                mapper,
                properties.model()
        );
    }
}
