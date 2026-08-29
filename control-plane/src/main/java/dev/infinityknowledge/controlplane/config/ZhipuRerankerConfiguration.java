package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuRerankConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuReranker;
import dev.infinityknowledge.spi.retrieval.Reranker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/**
 * 在显式选择 zhipu provider 时注册智谱专用文本精排适配器。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval.reranker",
        name = "provider",
        havingValue = "zhipu"
)
public class ZhipuRerankerConfiguration {

    /**
     * 创建只供精排调用使用的有界 HTTP 客户端，并支持独立代理配置。
     *
     * @param properties 智谱连接配置
     * @return 精排 HTTP 客户端
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.retrieval.reranker",
            name = "enabled",
            havingValue = "true"
    )
    HttpClient rerankerHttpClient(ZhipuRerankerProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout());
        if (!properties.proxyHost().isEmpty()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    properties.proxyHost(),
                    properties.proxyPort()
            )));
        }
        return builder.build();
    }

    /**
     * 将智谱协议适配为厂商无关的 Reranker SPI。
     *
     * @param providerProperties 智谱连接配置
     * @param rerankerProperties 厂商无关资源预算
     * @param httpClient 精排 HTTP 客户端
     * @return 智谱精排器
     */
    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.retrieval.reranker",
            name = "enabled",
            havingValue = "true"
    )
    Reranker zhipuReranker(
            ZhipuRerankerProperties providerProperties,
            RerankerProperties rerankerProperties,
            @Qualifier("rerankerHttpClient") HttpClient httpClient
    ) {
        return new ZhipuReranker(
                new ZhipuRerankConfig(
                        providerProperties.endpoint(),
                        providerProperties.apiKey(),
                        providerProperties.model(),
                        providerProperties.requestTimeout(),
                        Math.min(rerankerProperties.maxCandidates(), 128),
                        Math.min(rerankerProperties.maxQueryCharacters(), 4_096),
                        Math.min(rerankerProperties.maxCandidateCharacters(), 4_096),
                        rerankerProperties.maxTotalCharacters(),
                        providerProperties.maxAttempts(),
                        providerProperties.initialBackoff()
                ),
                httpClient,
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );
    }
}
