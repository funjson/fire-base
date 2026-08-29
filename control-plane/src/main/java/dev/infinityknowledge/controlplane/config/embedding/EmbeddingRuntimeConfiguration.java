package dev.infinityknowledge.controlplane.config.embedding;

import dev.infinityknowledge.controlplane.config.EmbeddingProperties;
import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuEmbeddingConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuEmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/**
 * 独立装配 Embedding 契约与执行能力，使语义切分、模型精排和 Milvus 不再互相绑定。
 *
 * <p>模型身份与维度属于索引代际契约，即使本进程未启用在线 Embedding 调用也必须存在；
 * 只有会发起外部请求的 HTTP 客户端和 Provider 受 {@code enabled} 开关控制。</p>
 */
@Configuration
public class EmbeddingRuntimeConfiguration {

    /**
     * 创建不可变的 Provider、模型与向量维度契约。
     */
    @Bean
    EmbeddingSpec embeddingSpec(EmbeddingProperties properties) {
        return new EmbeddingSpec(
                properties.provider(),
                properties.model(),
                properties.dimensions()
        );
    }

    /**
     * 创建支持可选代理和连接超时的 JDK HTTP 客户端。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.embedding",
            name = "enabled",
            havingValue = "true"
    )
    HttpClient embeddingHttpClient(EmbeddingProperties properties) {
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
     * 创建有界智谱 Embedding Provider；任何日志均不得包含凭据或正文。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.embedding",
            name = "enabled",
            havingValue = "true"
    )
    EmbeddingProvider embeddingProvider(
            EmbeddingProperties properties,
            HttpClient embeddingHttpClient
    ) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "embedding apiKey is required when embedding capability is enabled"
            );
        }
        return new ZhipuEmbeddingProvider(
                new ZhipuEmbeddingConfig(
                        properties.endpoint(),
                        properties.apiKey(),
                        properties.requestTimeout(),
                        properties.maxBatchSize(),
                        properties.maxAttempts(),
                        properties.initialBackoff()
                ),
                embeddingHttpClient,
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );
    }
}
