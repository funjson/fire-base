package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuCoverageJudge;
import dev.infinityknowledge.provider.zhipu.ZhipuGenerationConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuJsonGenerationClient;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
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
    CoverageJudge coverageJudge(CoverageJudgeProperties properties) {
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
        return new ZhipuCoverageJudge(
                new ZhipuJsonGenerationClient(
                        new ZhipuGenerationConfig(
                                properties.endpoint(),
                                properties.apiKey(),
                                properties.model(),
                                properties.requestTimeout(),
                                properties.maxAttempts(),
                                properties.initialBackoff(),
                                properties.maxInputCharacters(),
                                properties.maxOutputTokens()
                        ),
                        http.build(),
                        mapper,
                        new ThreadRetrySleeper()
                ),
                mapper
        );
    }
}
