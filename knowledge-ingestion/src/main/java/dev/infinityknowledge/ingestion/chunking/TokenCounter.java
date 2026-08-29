package dev.infinityknowledge.ingestion.chunking;

/**
 * 为 Chunk 大小约束提供可替换的 Token 计数契约。
 *
 * <p>实现可以返回模型的精确 Token 数，也可以返回明确命名的预算代理值；实现名称、
 * 版本和精确性必须进入处理契约。禁止用字符数或字节数冒充模型 Token 数。</p>
 */
public interface TokenCounter {

    /** 返回可持久化到 Space 配置的稳定 Tokenizer 标识。 */
    String id();

    /** 返回计数算法版本。 */
    String version();

    /** 返回供控制台解释取舍的中文说明。 */
    String description();

    /** 返回该实现是否与目标模型 Tokenizer 精确一致。 */
    boolean exactModelTokens();

    /**
     * 返回运维为该 Tokenizer 固定绑定的 Embedding Profile；空值表示未绑定具体模型。
     *
     * <p>非空值表达部署配置关系，不代表运行期向模型服务验证过一致性。只有绑定非空且
     * {@link #exactModelTokens()} 为 true 时，控制台才可以把 {@code maximumTokens}
     * 描述为对应模型的硬上限。</p>
     */
    default String modelProfileId() {
        return "";
    }

    /** 返回包含算法版本与精确性的稳定契约。 */
    default String contract() {
        return id() + ":" + version() + ":exact=" + exactModelTokens()
                + ":modelProfile=" + modelProfileId();
    }

    /** 返回文本的精确 Token 数，或由能力目录明确标注为非精确的预算估算值。 */
    int count(String text);

    /**
     * 返回指定范围内从 {@code startOffset} 起、计数不超过预算且实现可安全确定的
     * 尽量长绝对 UTF-16 结束偏移。
     *
     * <p>范围接口使 Slicer 无需为长文档反复复制剩余全文。实现不得返回半个代理项；
     * 预算不足以容纳第一个 Unicode Code Point 时返回 {@code startOffset}。子词模型的
     * 前缀计数未必单调，实现不需要扫描所有前缀证明数学上的全局最大值，但必须保证
     * 返回值不超过硬上限，并保持线性或有界资源消耗。</p>
     */
    int maximumPrefixEnd(
            String text,
            int startOffset,
            int endOffset,
            int maximumTokens
    );

}
