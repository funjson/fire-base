package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.spi.retrieval.Reranker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证智谱精排器只在显式启用并选择 zhipu provider 时注册。
 */
class ZhipuRerankerConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ZhipuRerankerConfiguration.class)
            .withBean(RerankerProperties.class, () -> new RerankerProperties(
                    true,
                    RerankerProperties.Provider.ZHIPU,
                    Duration.ofSeconds(4),
                    24,
                    4_096,
                    4_096,
                    100_000
            ))
            .withBean(ZhipuRerankerProperties.class, () -> new ZhipuRerankerProperties(
                    URI.create("https://open.bigmodel.cn/api/paas/v4/rerank"),
                    "test-key",
                    "rerank",
                    Duration.ofSeconds(3),
                    1,
                    Duration.ZERO,
                    "",
                    0
            ));

    /**
     * 默认未满足 zhipu 条件时不创建精排器。
     */
    @Test
    void remainsInactiveWithoutZhipuProviderSelection() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(Reranker.class));
    }

    /**
     * 显式启用 zhipu provider 后创建唯一 Reranker。
     */
    @Test
    void registersZhipuRerankerWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "infinity.knowledge.retrieval.reranker.enabled=true",
                        "infinity.knowledge.retrieval.reranker.provider=zhipu"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(Reranker.class));
    }
}
