package dev.infinityknowledge.spi.extraction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 真实 Extraction Dataset Runner 产生的非正文硬门禁报告。
 *
 * <p>报告保存指标、处理合同和稳定原因码，不保存 Golden Source、Element 正文或模型
 * 响应。{@code status} 只能是 PASSED、FAILED 或 ERROR；未执行时数据库保持空报告。</p>
 */
public record ExtractionGateReport(
        ExtractionGateStatus status,
        String datasetId,
        String datasetVersion,
        String configFingerprint,
        Instant evaluatedAt,
        List<CaseResult> cases,
        String errorCode
) {

    /** 保证“业务失败”和“执行错误”具有互斥、稳定的表达。 */
    public ExtractionGateReport {
        Objects.requireNonNull(status, "status must not be null");
        if (status == ExtractionGateStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException("NOT_EVALUATED must not have a report");
        }
        datasetId = required(datasetId, "datasetId", 128);
        datasetVersion = required(datasetVersion, "datasetVersion", 128);
        configFingerprint = sha256(configFingerprint, "configFingerprint");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        if (cases.size() > 1_000) {
            throw new IllegalArgumentException("gate report contains too many cases");
        }
        if (status == ExtractionGateStatus.ERROR) {
            errorCode = stableCode(errorCode, "errorCode");
        } else {
            if (errorCode != null) {
                throw new IllegalArgumentException("business gate report must not have errorCode");
            }
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("evaluated gate report must contain cases");
            }
            boolean allPassed = cases.stream().allMatch(CaseResult::passed);
            if ((status == ExtractionGateStatus.PASSED) != allPassed) {
                throw new IllegalArgumentException("gate status differs from case results");
            }
        }
    }

    /** 单个 Golden Case 的硬指标与稳定发现。 */
    public record CaseResult(
            String caseId,
            String sourceSha256,
            boolean passed,
            double parseSuccessRate,
            int tokenOverflowCount,
            int invalidSourceSpanCount,
            double sourceAccountingRate,
            int silentTruncationCount,
            List<Finding> findings
    ) {
        /** 指标允许表达失败，但不允许非有限数和负计数。 */
        public CaseResult {
            caseId = required(caseId, "caseId", 128);
            sourceSha256 = sha256(sourceSha256, "sourceSha256");
            if (!Double.isFinite(parseSuccessRate) || parseSuccessRate < 0.0D
                    || parseSuccessRate > 1.0D || tokenOverflowCount < 0
                    || invalidSourceSpanCount < 0 || !Double.isFinite(sourceAccountingRate)
                    || sourceAccountingRate < 0.0D || sourceAccountingRate > 1.0D
                    || silentTruncationCount < 0) {
                throw new IllegalArgumentException("gate case metrics are invalid");
            }
            findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
            if (findings.size() > 200 || passed != findings.isEmpty()) {
                throw new IllegalArgumentException("gate findings are inconsistent");
            }
        }
    }

    /** 不包含 Source 文本的稳定原因码和短说明。 */
    public record Finding(String code, String message) {
        /** 限制说明长度，防止异常响应或正文被当作报告写入。 */
        public Finding {
            code = stableCode(code, "code");
            message = required(message, "message", 512);
        }
    }

    private static String required(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }

    private static String stableCode(String value, String name) {
        String normalized = required(value, name, 128);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException(name + " is not a stable error code");
        }
        return normalized;
    }

    private static String sha256(String value, String name) {
        String normalized = required(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }
}
