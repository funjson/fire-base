package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuFeedbackQueryPlanner;
import dev.infinityknowledge.provider.zhipu.ZhipuGenerationConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuJsonGenerationClient;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/**
 * 在显式启用时装配固定 Chain 的有界反馈查询生成器。
 *
 * <p>首轮 TERM_EXPANSION 必须由独立 {@code TerminologyService} Adapter 提供，
 * 此配置只负责 Coverage 之后已经由固定 Chain 选定的反馈节点。</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval.feedback-planner",
        name = "enabled",
        havingValue = "true"
)
public class FeedbackPlannerConfiguration {

    /** 使用受控模型端点生成固定 Chain 已选节点的一条查询变体。 */
    @Bean
    @ConditionalOnMissingBean(FeedbackQueryPlanner.class)
    FeedbackQueryPlanner feedbackQueryPlanner(FeedbackPlannerProperties properties) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "feedback planner apiKey is required when model planning is enabled"
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
                properties.maxOutputTokens()
        );
        return new ZhipuFeedbackQueryPlanner(
                new ZhipuJsonGenerationClient(
                        generation,
                        http.build(),
                        mapper,
                        new ThreadRetrySleeper()
                ),
                mapper,
                properties.model()
        );
    }
}
