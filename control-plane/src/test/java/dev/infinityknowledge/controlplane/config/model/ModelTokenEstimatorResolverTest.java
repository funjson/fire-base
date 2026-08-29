package dev.infinityknowledge.controlplane.config.model;

import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证多个厂商计数器共存时只按精确 Provider 和模型选择。 */
class ModelTokenEstimatorResolverTest {

    @Test
    void selectsTheOnlyEstimatorSupportingTheRequestedProviderAndModel() {
        ModelTokenEstimator other = estimator("other", "other-model", "other-v1");
        ModelTokenEstimator zhipu = estimator("zhipu", "glm-5.2", "zhipu-v1");
        ObjectProvider<ModelTokenEstimator> providers = providers(other, zhipu);

        assertThat(ModelTokenEstimatorResolver.require(
                providers,
                "zhipu",
                "glm-5.2",
                "feedback planner"
        )).isSameAs(zhipu);
    }

    @Test
    void rejectsAmbiguousEstimatorsForTheSameModel() {
        ObjectProvider<ModelTokenEstimator> providers = providers(
                estimator("zhipu", "glm-5.2", "first-v1"),
                estimator("zhipu", "glm-5.2", "second-v1")
        );

        assertThatThrownBy(() -> ModelTokenEstimatorResolver.find(
                providers,
                "zhipu",
                "glm-5.2"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("multiple prompt token estimators support the configured provider and model");
    }

    @Test
    void rejectsMissingEstimatorForAnEnabledComponent() {
        ObjectProvider<ModelTokenEstimator> providers = providers(
                estimator("other", "other-model", "other-v1")
        );

        assertThatThrownBy(() -> ModelTokenEstimatorResolver.require(
                providers,
                "zhipu",
                "glm-5.2",
                "space router"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("space router requires a matching prompt token estimator");
    }

    private static ModelTokenEstimator estimator(
            String providerId,
            String modelId,
            String version
    ) {
        return new ModelTokenEstimator() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public Set<String> supportedModelIds() {
                return Set.of(modelId);
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public Duration maximumLatency() {
                return Duration.ZERO;
            }

            @Override
            public ModelTokenEstimate estimate(ModelTokenEstimateRequest request) {
                return new ModelTokenEstimate(1, true, version);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ModelTokenEstimator> providers(
            ModelTokenEstimator... estimators
    ) {
        ObjectProvider<ModelTokenEstimator> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(estimators));
        return provider;
    }
}
