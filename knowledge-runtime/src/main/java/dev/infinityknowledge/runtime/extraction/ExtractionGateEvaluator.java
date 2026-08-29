package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;

/**
 * TEST_ONLY Processor 调用的真实 Dataset 门禁端口。
 *
 * <p>接口位于 Runtime，避免 Runtime 反向依赖具体评测模块；Control Plane Adapter
 * 可以使用统一 ObservationFactory 与 Runner。Session 只持有范围和指标，不持有原文。</p>
 */
public interface ExtractionGateEvaluator {

    /** 为一个已经绑定 Dataset 的 Run 创建有界评测会话。 */
    Session start(RunSnapshot run, SpaceDocumentProcessingConfig config);

    /** 未安装 Dataset Adapter 时的显式空实现，只允许无 Dataset Run。 */
    static ExtractionGateEvaluator disabled() {
        return (run, config) -> {
            if (run.datasetId() != null) {
                throw new IllegalStateException("extraction gate evaluator is unavailable");
            }
            return new Session() {
                @Override
                public void observe(RunItem item, ExtractionResult result) {
                    // 未选择 Dataset，不生成 Observation。
                }

                @Override
                public ExtractionGateReport complete() {
                    return null;
                }
            };
        };
    }

    /** 单次任务的范围观测收集器。 */
    interface Session {
        /** 接收一个真实抽取结果，正文不得被写入日志或报告。 */
        void observe(RunItem item, ExtractionResult result);

        /** 执行统一硬门禁；未选 Dataset 时返回 null。 */
        ExtractionGateReport complete();
    }
}
