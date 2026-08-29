package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.controlplane.config.RerankerProperties;
import dev.infinityknowledge.controlplane.config.ZhipuRerankerProperties;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.CoverageJudgeComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.FeedbackQueryPlannerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.RerankerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.TerminologyServiceComponent;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.Reranker;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.Retriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

/**
 * 把当前部署的 Spring Bean 物化为厂商无关、可按 Space 精确选择的组件目录。
 *
 * <p>这里不做动态类加载；外部 Adapter 只需注册强类型 Profile Bean。运行时选择和
 * 观测都读取同一个 Profile，避免把配置字符串误报成实际执行模型。</p>
 */
@Configuration
public class RetrievalComponentRegistryConfiguration {

    /** 为当前唯一的反馈生成模型发布稳定 Provider、模型和 Prompt 合同。 */
    @Bean
    @ConditionalOnBean(FeedbackQueryPlanner.class)
    @ConditionalOnMissingBean(FeedbackQueryPlannerComponent.class)
    FeedbackQueryPlannerComponent feedbackQueryPlannerComponent(
            FeedbackQueryPlanner planner,
            FeedbackPlannerProperties properties
    ) {
        return new FeedbackQueryPlannerComponent(
                new RetrievalComponentVersion(
                        "feedback-query-planner",
                        "zhipu",
                        properties.model(),
                        "feedback-prompt-v1"
                ),
                planner
        );
    }

    /** 为当前 Coverage Judge 发布模型与 Prompt 版本，供 Space 配置精确匹配。 */
    @Bean
    @ConditionalOnBean(CoverageJudge.class)
    @ConditionalOnMissingBean(CoverageJudgeComponent.class)
    CoverageJudgeComponent coverageJudgeComponent(
            CoverageJudge judge,
            CoverageJudgeProperties properties
    ) {
        return new CoverageJudgeComponent(
                new RetrievalComponentVersion(
                        "coverage-judge",
                        "zhipu",
                        properties.model(),
                        "coverage-prompt-v1"
                ),
                judge
        );
    }

    /**
     * 为已选择的精排实现发布 Profile；Embedding 模式把实际 Embedding 合同写入模型标识。
     */
    @Bean
    @ConditionalOnBean(Reranker.class)
    @ConditionalOnMissingBean(RerankerComponent.class)
    RerankerComponent rerankerComponent(
            Reranker reranker,
            RerankerProperties properties,
            ZhipuRerankerProperties zhipuProperties,
            ObjectProvider<EmbeddingSpec> embeddingSpec
    ) {
        return switch (properties.provider()) {
            case ZHIPU -> new RerankerComponent(
                    new RetrievalComponentVersion(
                            "reranker",
                            "zhipu",
                            zhipuProperties.model(),
                            "passage-template-v1"
                    ),
                    reranker
            );
            case EMBEDDING -> {
                EmbeddingSpec spec = embeddingSpec.getIfAvailable();
                if (spec == null) {
                    throw new IllegalStateException(
                            "embedding reranker profile requires an EmbeddingSpec"
                    );
                }
                yield new RerankerComponent(
                        new RetrievalComponentVersion(
                                "reranker",
                                "embedding",
                                spec.providerId() + ":" + spec.modelId(),
                                "cosine-v1"
                        ),
                        reranker
                );
            }
        };
    }

    /**
     * 汇总显式 Profile；未安装的能力保持为空，只有 Space 启用时才被目录拒绝。
     */
    @Bean
    RetrievalComponentRegistry retrievalComponentRegistry(
            List<TerminologyServiceComponent> terminologyServices,
            Optional<FeedbackQueryPlannerComponent> feedbackQueryPlanner,
            List<RerankerComponent> rerankers,
            List<CoverageJudgeComponent> coverageJudges,
            List<Retriever> retrievers
    ) {
        return new RetrievalComponentRegistry(
                terminologyServices,
                feedbackQueryPlanner,
                rerankers,
                coverageJudges,
                retrievers
        );
    }
}
