package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.BoundaryExpectation;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.BoundaryRelation;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.Case;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.ExpectedElement;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceReport.CaseReport;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceReport.Finding;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Chunk;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Disposition;
import dev.infinityknowledge.evaluation.extraction.ExtractionObservation.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对归一化抽取结果执行确定性 Golden Dataset 验收。
 *
 * <p>Runner 不调用模型或外部存储。线上实验、离线 CI 和 Space 测试广场应调用
 * 同一个抽取引擎，再把结果转换为 {@link ExtractionObservation} 交给这里，确保
 * 页面调优与发布门禁使用同一套判定规则。</p>
 */
public final class ExtractionAcceptanceRunner {

    /** 对数据集中的所有 Case 执行验收。 */
    public ExtractionAcceptanceReport run(
            ExtractionAcceptanceDataset dataset,
            List<ExtractionObservation> observations
    ) {
        Objects.requireNonNull(dataset, "dataset must not be null");
        Map<String, ExtractionObservation> observationsByCase = index(observations);
        Set<String> caseIds = new HashSet<>();
        List<CaseReport> reports = new ArrayList<>(dataset.cases().size());
        for (Case expected : dataset.cases()) {
            caseIds.add(expected.id());
            reports.add(evaluate(expected, observationsByCase.get(expected.id())));
        }
        Set<String> unknownCases = new HashSet<>(observationsByCase.keySet());
        unknownCases.removeAll(caseIds);
        if (!unknownCases.isEmpty()) {
            throw new IllegalArgumentException("observations contain unknown case ids: " + unknownCases);
        }
        return new ExtractionAcceptanceReport(dataset.datasetVersion(), reports);
    }

    private static CaseReport evaluate(Case expected, ExtractionObservation actual) {
        if (actual == null) {
            return new CaseReport(
                    expected.id(),
                    expected.sourceSha256(),
                    null,
                    0.0D,
                    0,
                    0,
                    0.0D,
                    expected.mustPreserve().size(),
                    List.of(new Finding("OBSERVATION_MISSING", "没有找到该样例的抽取结果"))
            );
        }

        if (!expected.sourceSha256().equals(actual.sourceSha256())) {
            return new CaseReport(
                    expected.id(),
                    actual.sourceSha256(),
                    actual.processingContracts(),
                    0.0D,
                    0,
                    0,
                    0.0D,
                    expected.mustPreserve().size(),
                    List.of(new Finding(
                            "OBSERVATION_SOURCE_MISMATCH",
                            "观测结果的 Source SHA-256 与 Golden Case 不一致"
                    ))
            );
        }

        if (!expected.artifactSha256().equals(actual.artifactSha256())
                || !expected.artifactContract().equals(actual.artifactContract())
                || expected.artifactText().length() != actual.artifactLength()) {
            return new CaseReport(
                    expected.id(),
                    actual.sourceSha256(),
                    actual.processingContracts(),
                    0.0D,
                    0,
                    0,
                    0.0D,
                    expected.mustPreserve().size(),
                    List.of(new Finding(
                            "OBSERVATION_ARTIFACT_MISMATCH",
                            "观测结果的规范化 Artifact 与 Golden Case 不一致"
                    ))
            );
        }

        List<Finding> findings = new ArrayList<>();
        int sourceLength = expected.artifactText().length();
        double parseSuccessRate = actual.parseSucceeded() ? 1.0D : 0.0D;
        int tokenOverflows = tokenOverflowCount(actual, findings);
        int invalidSpans = invalidSourceSpanCount(actual, sourceLength);
        double accountingRate = sourceAccountingRate(expected, actual, sourceLength);
        int silentTruncations = silentTruncationCount(expected, actual, sourceLength);

        evaluateElements(expected, actual, findings);
        evaluateBoundaries(expected, actual, sourceLength, findings);
        evaluateCleaning(expected, actual, sourceLength, findings);
        evaluateHardGates(expected, parseSuccessRate, tokenOverflows, invalidSpans,
                accountingRate, silentTruncations, findings);

        return new CaseReport(
                expected.id(),
                actual.sourceSha256(),
                actual.processingContracts(),
                parseSuccessRate,
                tokenOverflows,
                invalidSpans,
                accountingRate,
                silentTruncations,
                findings
        );
    }

    private static void evaluateElements(
            Case expected,
            ExtractionObservation actual,
            List<Finding> findings
    ) {
        for (ExpectedElement label : expected.expectedElements()) {
            boolean matched = actual.elements().stream().anyMatch(element ->
                    contains(element.sourceRange(), label.range())
                            && element.type() == label.type()
                            && (label.role() == null || Objects.equals(label.role(), element.role()))
            );
            if (!matched) {
                findings.add(new Finding(
                        "ELEMENT_EXPECTATION_MISSED",
                        "未匹配元素标签 " + label.type() + "@" + describe(label.range())
                ));
            }
        }
    }

    private static void evaluateBoundaries(
            Case expected,
            ExtractionObservation actual,
            int sourceLength,
            List<Finding> findings
    ) {
        for (BoundaryExpectation boundary : expected.boundaries()) {
            boolean joined = actual.chunks().stream().anyMatch(chunk ->
                    covers(boundary.left(), chunk.sourceSpans(), sourceLength)
                            && covers(boundary.right(), chunk.sourceSpans(), sourceLength)
            );
            if (boundary.relation() == BoundaryRelation.MUST_BREAK && joined) {
                findings.add(new Finding(
                        "MUST_BREAK_VIOLATED",
                        "Chunk 跨越了必须断开的范围 " + describe(boundary.left())
                                + " -> " + describe(boundary.right())
                ));
            } else if (boundary.relation() == BoundaryRelation.MUST_JOIN && !joined) {
                findings.add(new Finding(
                        "MUST_JOIN_VIOLATED",
                        "没有 Chunk 同时覆盖必须合并的范围 " + describe(boundary.left())
                                + " -> " + describe(boundary.right())
                ));
            }
        }
    }

    private static void evaluateCleaning(
            Case expected,
            ExtractionObservation actual,
            int sourceLength,
            List<Finding> findings
    ) {
        List<SourceRange> indexableElements = actual.elements().stream()
                .filter(element -> element.disposition() == Disposition.INDEXABLE)
                .map(Element::sourceRange)
                .toList();
        List<SourceRange> chunkSpans = actual.chunks().stream()
                .flatMap(chunk -> chunk.sourceSpans().stream())
                .toList();
        List<SourceRange> nonIndexable = new ArrayList<>(actual.removedRanges());
        actual.elements().stream()
                .filter(element -> element.disposition() == Disposition.METADATA_ONLY)
                .map(Element::sourceRange)
                .forEach(nonIndexable::add);

        for (SourceRange preserved : expected.mustPreserve()) {
            if (!covers(preserved, indexableElements, sourceLength)
                    || !covers(preserved, chunkSpans, sourceLength)) {
                findings.add(new Finding(
                        "PRESERVE_RANGE_MISSING",
                        "必须保留范围未完整进入可检索 Chunk " + describe(preserved)
                ));
            }
        }
        for (SourceRange removed : expected.mustRemove()) {
            boolean leaked = overlapsAny(removed, indexableElements, sourceLength)
                    || overlapsAny(removed, chunkSpans, sourceLength);
            if (leaked) {
                findings.add(new Finding(
                        "REMOVE_RANGE_LEAKED",
                        "必须删除或元数据化的范围进入了索引 " + describe(removed)
                ));
            }
            if (!covers(removed, nonIndexable, sourceLength)) {
                findings.add(new Finding(
                        "REMOVE_RANGE_UNACCOUNTED",
                        "必须删除范围既未明确删除，也未标为元数据 " + describe(removed)
                ));
            }
        }
    }

    private static void evaluateHardGates(
            Case expected,
            double parseSuccessRate,
            int tokenOverflows,
            int invalidSpans,
            double accountingRate,
            int silentTruncations,
            List<Finding> findings
    ) {
        var gates = expected.hardGates();
        if (parseSuccessRate < gates.minimumParseSuccessRate()) {
            findings.add(new Finding("PARSE_SUCCESS_GATE_FAILED", "解析成功率未达到硬门禁"));
        }
        if (tokenOverflows > gates.maximumTokenOverflowCount()) {
            findings.add(new Finding("TOKEN_OVERFLOW_GATE_FAILED", "存在超过 Token 硬上限的 Chunk"));
        }
        if (invalidSpans > gates.maximumInvalidSourceSpanCount()) {
            findings.add(new Finding("SOURCE_SPAN_GATE_FAILED", "存在非法或无法追溯的 SourceSpan"));
        }
        if (accountingRate + 1.0E-12D < gates.minimumSourceAccountingRate()) {
            findings.add(new Finding("SOURCE_ACCOUNTING_GATE_FAILED", "语义来源核算率未达到硬门禁"));
        }
        if (silentTruncations > gates.maximumSilentTruncationCount()) {
            findings.add(new Finding("SILENT_TRUNCATION_GATE_FAILED", "存在未报告的正文截断"));
        }
    }

    private static int tokenOverflowCount(
            ExtractionObservation actual,
            List<Finding> findings
    ) {
        int overflowCount = 0;
        for (Chunk chunk : actual.chunks()) {
            if (chunk.ordinal() < 0 || chunk.tokenCount() < 0 || chunk.tokenLimit() < 1) {
                findings.add(new Finding(
                        "CHUNK_METRIC_INVALID",
                        "Chunk 顺序或 Token 计数配置非法，ordinal=" + chunk.ordinal()
                ));
                continue;
            }
            if (chunk.tokenCount() > chunk.tokenLimit()) {
                overflowCount++;
            }
        }
        return overflowCount;
    }

    private static int invalidSourceSpanCount(
            ExtractionObservation actual,
            int sourceLength
    ) {
        int count = 0;
        for (Element element : actual.elements()) {
            if (!element.sourceRange().isValidFor(sourceLength)) {
                count++;
            }
        }
        for (SourceRange range : actual.removedRanges()) {
            if (!range.isValidFor(sourceLength)) {
                count++;
            }
        }
        for (SourceRange range : actual.reportedTruncationRanges()) {
            if (!range.isValidFor(sourceLength)) {
                count++;
            }
        }
        List<SourceRange> indexableElements = actual.elements().stream()
                .filter(element -> element.disposition() == Disposition.INDEXABLE)
                .map(Element::sourceRange)
                .filter(range -> range.isValidFor(sourceLength))
                .toList();
        for (Chunk chunk : actual.chunks()) {
            if (chunk.sourceSpans().isEmpty()) {
                count++;
                continue;
            }
            for (SourceRange span : chunk.sourceSpans()) {
                if (!span.isValidFor(sourceLength)
                        || !indexableElements.stream().anyMatch(element -> contains(element, span))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double sourceAccountingRate(
            Case expected,
            ExtractionObservation actual,
            int sourceLength
    ) {
        List<SourceRange> expectedRanges = new ArrayList<>(expected.mustPreserve());
        expectedRanges.addAll(expected.mustRemove());
        List<SourceRange> expectedUnion = merge(expectedRanges, sourceLength);
        long expectedLength = totalLength(expectedUnion);
        if (expectedLength == 0L) {
            return 1.0D;
        }
        List<SourceRange> accounted = actual.elements().stream()
                .map(Element::sourceRange)
                .filter(range -> range.isValidFor(sourceLength))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        actual.removedRanges().stream()
                .filter(range -> range.isValidFor(sourceLength))
                .forEach(accounted::add);
        List<SourceRange> intersections = intersections(expectedUnion, merge(accounted, sourceLength));
        return (double) totalLength(intersections) / expectedLength;
    }

    private static int silentTruncationCount(
            Case expected,
            ExtractionObservation actual,
            int sourceLength
    ) {
        List<SourceRange> chunkSpans = actual.chunks().stream()
                .flatMap(chunk -> chunk.sourceSpans().stream())
                .toList();
        int count = 0;
        for (SourceRange preserved : expected.mustPreserve()) {
            boolean missing = !covers(preserved, chunkSpans, sourceLength);
            boolean reported = overlapsAny(
                    preserved,
                    actual.reportedTruncationRanges(),
                    sourceLength
            );
            if (missing && !reported) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, ExtractionObservation> index(
            List<ExtractionObservation> observations
    ) {
        Objects.requireNonNull(observations, "observations must not be null");
        Map<String, ExtractionObservation> indexed = new HashMap<>();
        for (ExtractionObservation observation : observations) {
            Objects.requireNonNull(observation, "observation must not be null");
            if (indexed.putIfAbsent(observation.caseId(), observation) != null) {
                throw new IllegalArgumentException(
                        "duplicate extraction observation: " + observation.caseId()
                );
            }
        }
        return indexed;
    }

    private static boolean covers(
            SourceRange expected,
            List<SourceRange> actual,
            int sourceLength
    ) {
        int cursor = expected.startOffset();
        for (SourceRange range : merge(actual, sourceLength)) {
            if (range.endOffset() <= cursor) {
                continue;
            }
            if (range.startOffset() > cursor) {
                return false;
            }
            cursor = Math.max(cursor, range.endOffset());
            if (cursor >= expected.endOffset()) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAny(
            SourceRange expected,
            List<SourceRange> actual,
            int sourceLength
    ) {
        return actual.stream()
                .filter(range -> range.isValidFor(sourceLength))
                .anyMatch(expected::overlaps);
    }

    private static List<SourceRange> merge(List<SourceRange> ranges, int sourceLength) {
        List<SourceRange> sorted = ranges.stream()
                .filter(Objects::nonNull)
                .filter(range -> range.isValidFor(sourceLength))
                .sorted(Comparator.comparingInt(SourceRange::startOffset)
                        .thenComparingInt(SourceRange::endOffset))
                .toList();
        if (sorted.isEmpty()) {
            return List.of();
        }
        List<SourceRange> merged = new ArrayList<>();
        int start = sorted.getFirst().startOffset();
        int end = sorted.getFirst().endOffset();
        for (int index = 1; index < sorted.size(); index++) {
            SourceRange next = sorted.get(index);
            if (next.startOffset() <= end) {
                end = Math.max(end, next.endOffset());
            } else {
                merged.add(new SourceRange(start, end));
                start = next.startOffset();
                end = next.endOffset();
            }
        }
        merged.add(new SourceRange(start, end));
        return List.copyOf(merged);
    }

    private static List<SourceRange> intersections(
            List<SourceRange> left,
            List<SourceRange> right
    ) {
        List<SourceRange> values = new ArrayList<>();
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.size() && rightIndex < right.size()) {
            SourceRange first = left.get(leftIndex);
            SourceRange second = right.get(rightIndex);
            int start = Math.max(first.startOffset(), second.startOffset());
            int end = Math.min(first.endOffset(), second.endOffset());
            if (start < end) {
                values.add(new SourceRange(start, end));
            }
            if (first.endOffset() <= second.endOffset()) {
                leftIndex++;
            } else {
                rightIndex++;
            }
        }
        return values;
    }

    private static long totalLength(List<SourceRange> ranges) {
        return ranges.stream()
                .mapToLong(range -> (long) range.endOffset() - range.startOffset())
                .sum();
    }

    private static boolean contains(SourceRange container, SourceRange value) {
        return container.startOffset() <= value.startOffset()
                && container.endOffset() >= value.endOffset();
    }

    private static String describe(SourceRange range) {
        return '[' + Integer.toString(range.startOffset()) + ',' + range.endOffset() + ')';
    }
}
