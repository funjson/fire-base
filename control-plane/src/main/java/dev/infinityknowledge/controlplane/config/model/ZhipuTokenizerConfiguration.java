package dev.infinityknowledge.controlplane.config.model;

import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuModelTokenEstimator;
import dev.infinityknowledge.provider.zhipu.ZhipuTokenizerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/** 在显式启用时安装独立于 Chunk Tokenizer 的智谱 Prompt Token 计数适配器。 */
@Configuration
@EnableConfigurationProperties(ZhipuTokenizerProperties.class)
@ConditionalOnProperty(
        prefix = "infinity.knowledge.model-tokenizers.zhipu",
        name = "enabled",
        havingValue = "true"
)
public class ZhipuTokenizerConfiguration {

    /** 创建可供所有 GLM 在线生成调用复用的模型计数端口。 */
    @Bean
    @ConditionalOnMissingBean(ZhipuModelTokenEstimator.class)
    ZhipuModelTokenEstimator zhipuModelTokenEstimator(ZhipuTokenizerProperties properties) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Zhipu tokenizer apiKey is required when prompt token estimation is enabled"
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
        return new ZhipuModelTokenEstimator(
                new ZhipuTokenizerConfig(
                        properties.endpoint(),
                        properties.apiKey(),
                        properties.requestTimeout(),
                        properties.maxAttempts(),
                        properties.initialBackoff(),
                        properties.maximumInputCharacters()
                ),
                http.build(),
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );
    }
}
