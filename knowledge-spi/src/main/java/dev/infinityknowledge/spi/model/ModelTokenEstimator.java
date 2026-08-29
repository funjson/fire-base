package dev.infinityknowledge.spi.model;

import java.time.Duration;
import java.util.Set;

/**
 * 为在线模型 Prompt 提供可替换的精确 Token 计数端口。
 *
 * <p>该端口只负责完整模型消息的计数和预算校验，不提供字符偏移，不能替代
 * 摄取链路中负责 Chunk 硬切的 {@code TokenCounter}。</p>
 */
public interface ModelTokenEstimator {

    /** 返回稳定 Provider 标识。 */
    String providerId();

    /** 返回当前适配器明确支持的模型集合。 */
    Set<String> supportedModelIds();

    /** 返回不含端点、凭据或正文的稳定计数合同版本。 */
    String version();

    /**
     * 返回一次计数调用在内部超时、重试和退避全部耗尽时的最坏耗时预算。
     * 本地确定性实现必须显式返回零值，避免新增远程 Adapter 时遗漏超时声明。
     */
    Duration maximumLatency();

    /** 使用请求中生成模型对应的 Tokenizer 计算完整 Prompt。 */
    ModelTokenEstimate estimate(ModelTokenEstimateRequest request);

    /** 返回当前适配器是否能精确处理指定 Provider 和模型。 */
    default boolean supports(String requestedProviderId, String requestedModelId) {
        return providerId().equals(requestedProviderId)
                && supportedModelIds().contains(requestedModelId);
    }
}
