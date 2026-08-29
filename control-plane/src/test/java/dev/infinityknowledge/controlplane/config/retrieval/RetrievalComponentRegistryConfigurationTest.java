package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.controlplane.config.RerankerProperties;
import dev.infinityknowledge.controlplane.config.ZhipuRerankerProperties;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.Reranker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Spring 装配发布的组件合同与实际模型 Bean、配置版本保持一致。 */
class RetrievalComponentRegistryConfigurationTest {

    private final RetrievalComponentRegistryConfiguration configuration =
            new RetrievalComponentRegistryConfiguration();

    @Test
    void publishesConfiguredModelAndPromptVersions() {
        FeedbackQueryPlanner planner = mock(FeedbackQueryPlanner.class);
        CoverageJudge judge = mock(CoverageJudge.class);
        Reranker reranker = mock(Reranker.class);

        var feedback = configuration.feedbackQueryPlannerComponent(
                planner,
                feedbackPlannerProperties("glm-planner-v2")
        );
        var coverage = configuration.coverageJudgeComponent(
                judge,
                coverageProperties("glm-coverage-v3")
        );
        var rerankerProfile = configuration.rerankerComponent(
                reranker,
                rerankerProperties(RerankerProperties.Provider.ZHIPU),
                zhipuRerankerProperties("rerank-v4"),
                emptyEmbeddingSpecProvider()
        );

        assertEquals("zhipu", feedback.version().provider());
        assertEquals("glm-planner-v2", feedback.version().model());
        assertEquals("feedback-prompt-v1", feedback.version().version());
        assertEquals("glm-coverage-v3", coverage.version().model());
        assertEquals("coverage-prompt-v1", coverage.version().version());
        assertEquals("rerank-v4", rerankerProfile.version().model());
        assertEquals("passage-template-v1", rerankerProfile.version().version());
        assertSame(reranker, rerankerProfile.implementation());
    }

    @Test
    void embeddingRerankerPublishesTheExactEmbeddingContract() {
        Reranker reranker = mock(Reranker.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingSpec> embeddingProvider = mock(ObjectProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(
                new EmbeddingSpec("zhipu", "embedding-3", 2_048)
        );

        var profile = configuration.rerankerComponent(
                reranker,
                rerankerProperties(RerankerProperties.Provider.EMBEDDING),
                zhipuRerankerProperties("unused"),
                embeddingProvider
        );

        assertEquals("embedding", profile.version().provider());
        assertEquals("zhipu:embedding-3", profile.version().model());
        assertEquals("cosine-v1", profile.version().version());
        assertSame(reranker, profile.implementation());
    }

    private static FeedbackPlannerProperties feedbackPlannerProperties(String model) {
        return new FeedbackPlannerProperties(
                true,
                Duration.ofSeconds(4),
                endpoint(),
                "",
                model,
                Duration.ofSeconds(3),
                1,
                Duration.ZERO,
                24_000,
                512,
                "",
                0
        );
    }

    private static CoverageJudgeProperties coverageProperties(String model) {
        return new CoverageJudgeProperties(
                true,
                Duration.ofSeconds(7),
                endpoint(),
                "",
                model,
                Duration.ofSeconds(6),
                1,
                Duration.ZERO,
                120_000,
                2_048,
                "",
                0
        );
    }

    private static RerankerProperties rerankerProperties(
            RerankerProperties.Provider provider
    ) {
        return new RerankerProperties(
                true,
                provider,
                Duration.ofSeconds(5),
                24,
                4_096,
                4_096,
                100_000
        );
    }

    private static ZhipuRerankerProperties zhipuRerankerProperties(String model) {
        return new ZhipuRerankerProperties(
                URI.create("https://example.invalid/rerank"),
                "",
                model,
                Duration.ofSeconds(3),
                1,
                Duration.ZERO,
                "",
                0
        );
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmbeddingSpec> emptyEmbeddingSpecProvider() {
        return mock(ObjectProvider.class);
    }

    private static URI endpoint() {
        return URI.create("https://example.invalid/chat");
    }
}
