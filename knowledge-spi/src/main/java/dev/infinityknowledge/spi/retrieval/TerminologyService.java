package dev.infinityknowledge.spi.retrieval;

/**
 * 为当前 Space 的受控术语资源执行在线等价术语扩展。
 *
 * <p>该端口不是通用 LLM Query Planner。实现应完成 Mention 链接、消歧和等价关系
 * 过滤，并且最多返回一个合并后的 Q1；没有可靠增量时返回空结果。</p>
 */
@FunctionalInterface
public interface TerminologyService {

    /**
     * 使用请求指定的资源和上限查找受控等价术语。
     *
     * @param request 术语扩展请求
     * @return 有界术语结果
     */
    TerminologyExpansionResult expand(TerminologyExpansionRequest request);

    /** 返回不访问外部资源、稳定产生空增量的默认实现。 */
    static TerminologyService unavailable() {
        return ignored -> TerminologyExpansionResult.none("unavailable", "none");
    }
}
