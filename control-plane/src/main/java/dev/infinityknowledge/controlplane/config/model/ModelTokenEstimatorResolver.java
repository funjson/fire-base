package dev.infinityknowledge.controlplane.config.model;

import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

/**
 * 按 Provider 和模型唯一解析 Prompt Token 计数器。
 *
 * <p>允许不同厂商 Adapter 同时注册；同一模型存在两个实现时拒绝启动，避免计数合同随机漂移。</p>
 */
public final class ModelTokenEstimatorResolver {

    private ModelTokenEstimatorResolver() {
    }

    /** 返回支持指定 Provider 和模型的唯一实现；没有匹配时返回空。 */
    public static Optional<ModelTokenEstimator> find(
            ObjectProvider<ModelTokenEstimator> providers,
            String providerId,
            String modelId
    ) {
        List<ModelTokenEstimator> matches = providers.orderedStream()
                .filter(estimator -> estimator.supports(providerId, modelId))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "multiple prompt token estimators support the configured provider and model"
            );
        }
        return matches.stream().findFirst();
    }

    /** 返回支持指定 Provider 和模型的唯一实现；缺失时拒绝启用对应在线模型阶段。 */
    public static ModelTokenEstimator require(
            ObjectProvider<ModelTokenEstimator> providers,
            String providerId,
            String modelId,
            String componentName
    ) {
        return find(providers, providerId, modelId)
                .orElseThrow(() -> new IllegalStateException(
                        componentName + " requires a matching prompt token estimator"
                ));
    }
}
