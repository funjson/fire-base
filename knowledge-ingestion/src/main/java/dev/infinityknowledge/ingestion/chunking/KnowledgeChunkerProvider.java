package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;

import java.util.Set;

/**
 * 把一个稳定 Provider 标识适配为受限的 {@link ChunkBoundaryStrategy}。
 *
 * <p>Provider 只声明真实实现能力，不承载任意配置 Schema。首个外部 Adapter
 * 接入时应根据它的真实参数扩展配置，而不是提前建立无法验证的通用插件协议。</p>
 */
public interface KnowledgeChunkerProvider {

    /** 返回可持久化到 Space 配置的稳定标识。 */
    String id();

    /** 返回 Provider 实现版本；实现语义变化时必须更新。 */
    String version();

    /**
     * 返回不可用的稳定原因码；空字符串表示当前部署可用。
     *
     * <p>Provider 即使不可用也保留在目录中，让控制台能够解释已有配置。</p>
     */
    String unavailableReason();

    /** 返回该 Provider 对每一个有效 Parser 都要求具备的输出能力。 */
    Set<ParserOutputCapability> requiredParserCapabilities();

    /**
     * 返回控制台创建配置时使用的 canonical JSON Object 默认值。
     *
     * <p>不可用 Provider 也必须返回可解释的同结构默认值，但控制面不得保存不可用
     * Provider 的配置。</p>
     */
    String defaultConfigurationJson();

    /**
     * 按已经过公共边界校验的 Space 配置创建一次不可变边界策略。
     *
     * <p>Provider 必须严格校验自己的 canonical JSON，且无权返回可绕过平台结构
     * 规划与统一物化的完整 KnowledgeChunker。</p>
     */
    ChunkBoundaryStrategy create(ChunkerConfiguration configuration);
}
