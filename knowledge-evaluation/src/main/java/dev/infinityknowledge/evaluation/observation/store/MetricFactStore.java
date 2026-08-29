package dev.infinityknowledge.evaluation.observation.store;

import dev.infinityknowledge.evaluation.observation.MetricFact;

import java.util.List;

/**
 * 版本化指标事实的可替换存储端口。
 *
 * <p>追加型事实需要建立稳定身份。身份相同且内容相同是幂等重投，身份相同但数值、
 * 维度或时间不同必须显式报冲突。执行级快照使用独立替换方法，不得和追加事实共用
 * 生命周期。</p>
 */
public interface MetricFactStore {

    /** 批量追加指标事实；调用方负责提供事务边界。 */
    AppendSummary appendAll(List<? extends MetricFact> facts);

    /**
     * 原子替换一个 execution 的完整指标快照。
     *
     * <p>该列表必须非空、全部属于同一租户和 execution，且同一指标键、定义版本和
     * 聚合方式只能出现一次。实现必须删除该 execution 的旧快照后写入新快照；逐事件
     * 追加事实和离线 Gold 事实不得受影响。调用方负责提供事务边界。</p>
     */
    void replaceExecutionSnapshot(List<MetricFact.Runtime> facts);

    /** 返回本批次中新写入和已存在的事实数。 */
    record AppendSummary(int appended, int alreadyPresent) {
        /** 校验汇总计数。 */
        public AppendSummary {
            if (appended < 0 || alreadyPresent < 0) {
                throw new IllegalArgumentException("append summary counts must not be negative");
            }
        }
    }
}
