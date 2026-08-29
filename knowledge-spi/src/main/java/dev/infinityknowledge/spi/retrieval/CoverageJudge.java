package dev.infinityknowledge.spi.retrieval;

/** 使用有限候选内容判断证据覆盖并重新选择最终候选。 */
@FunctionalInterface
public interface CoverageJudge {

    /**
     * 对本轮工作集执行一次结构化 Coverage 判断。
     *
     * @param request 不含阈值和排序分数的有界请求
     * @return 覆盖、缺口和最终有序候选标识
     */
    CoverageJudgmentResult judge(CoverageJudgmentRequest request);

    /** 返回明确不可用的实现；仅在 Space 未启用 Coverage 时不会被调用。 */
    static CoverageJudge unavailable() {
        return ignored -> {
            throw new IllegalStateException("coverage judge is not configured");
        };
    }
}
