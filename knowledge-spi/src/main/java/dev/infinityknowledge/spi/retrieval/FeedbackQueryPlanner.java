package dev.infinityknowledge.spi.retrieval;

/** 为固定 Chain 中已经由程序选定的节点生成一条查询表示。 */
@FunctionalInterface
public interface FeedbackQueryPlanner {

    /**
     * 生成指定策略的一条变体；实现不能选择或改变策略。
     */
    FeedbackQueryPlanningResult plan(FeedbackQueryPlanningRequest request);

    /** 返回明确不可用的实现，节点失败后 Runner 会继续扫描固定 Chain。 */
    static FeedbackQueryPlanner unavailable() {
        return ignored -> {
            throw new IllegalStateException("feedback query planner is not configured");
        };
    }
}
