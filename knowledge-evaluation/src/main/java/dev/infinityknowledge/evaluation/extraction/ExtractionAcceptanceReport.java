package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.ProcessingContracts;

import java.util.List;
import java.util.Objects;

/**
 * 数据抽取验收报告。
 *
 * @param datasetVersion 数据集版本
 * @param cases 每个样例的指标和失败原因
 */
public record ExtractionAcceptanceReport(
        String datasetVersion,
        List<CaseReport> cases
) {

    /** 防御性复制报告明细。 */
    public ExtractionAcceptanceReport {
        Objects.requireNonNull(datasetVersion, "datasetVersion must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
    }

    /** 只有所有样例均通过，整套数据集才通过。 */
    public boolean passed() {
        return cases.stream().allMatch(CaseReport::passed);
    }

    /**
     * 单个样例的关键硬指标。
     *
     * @param caseId 样例标识
     * @param sourceSha256 报告对应的 Golden Source SHA-256
     * @param processingContracts 生成观测时实际声明的处理合同；缺少观测时为 {@code null}
     * @param parseSuccessRate 解析成功率
     * @param tokenOverflowCount Token 超限 Chunk 数
     * @param invalidSourceSpanCount 非法或无法追溯到元素的范围数
     * @param sourceAccountingRate 已明确保留、元数据化或删除的语义来源比例
     * @param silentTruncationCount 未报告但未进入最终 Chunk 的必须保留范围数
     * @param findings 失败原因码和说明
     */
    public record CaseReport(
            String caseId,
            String sourceSha256,
            ProcessingContracts processingContracts,
            double parseSuccessRate,
            int tokenOverflowCount,
            int invalidSourceSpanCount,
            double sourceAccountingRate,
            int silentTruncationCount,
            List<Finding> findings
    ) {
        /** 复制发现列表。 */
        public CaseReport {
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(sourceSha256, "sourceSha256 must not be null");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
        }

        /** 无任何失败发现表示样例通过。 */
        public boolean passed() {
            return findings.isEmpty();
        }
    }

    /**
     * 稳定失败原因。
     *
     * @param code 供 CI 和控制台聚合的稳定原因码
     * @param message 供人工定位的非正文说明
     */
    public record Finding(String code, String message) {
        /** 原因码与说明不能为空。 */
        public Finding {
            Objects.requireNonNull(code, "finding code must not be null");
            Objects.requireNonNull(message, "finding message must not be null");
        }
    }
}
