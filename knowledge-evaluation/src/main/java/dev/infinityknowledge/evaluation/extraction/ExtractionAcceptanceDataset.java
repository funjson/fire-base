package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.domain.document.ElementType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可版本化的数据抽取 Golden Dataset。
 *
 * <p>数据集只描述稳定的业务期望，不保存某次运行生成的 ElementId 或 ChunkId。
 * 每个标签都绑定经人工复核的规范化 Artifact 字符范围，因此可以用于不同 Cleaner、
 * Chunker 和 Tokenizer 组合之间的 Baseline/Candidate 对比。更换 Parser 或 Artifact
 * 合同时必须生成新的 Dataset 版本，不能把旧范围解释到新的坐标空间。</p>
 *
 * @param datasetVersion 数据集契约版本
 * @param cases 验收样例
 */
public record ExtractionAcceptanceDataset(
        String datasetVersion,
        List<Case> cases
) {

    /** 校验数据集标识和样例标识唯一性。 */
    public ExtractionAcceptanceDataset {
        datasetVersion = required(datasetVersion, "datasetVersion");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("extraction dataset must contain cases");
        }
        Set<String> identifiers = cases.stream().map(Case::id).collect(Collectors.toSet());
        if (identifiers.size() != cases.size()) {
            throw new IllegalArgumentException("extraction case id must be unique");
        }
    }

    /**
     * 一个源文件的全部结构、清洗、边界与硬门禁期望。
     *
     * @param id 稳定样例标识
     * @param description 人类可读的覆盖意图
     * @param sourceName Golden Source 文件名
     * @param sourceBytes Golden Source 原始字节，供真实 ExtractionEngine 执行
     * @param sourceSha256 Golden Source 原始字节的 SHA-256
     * @param artifactText 人工复核的规范化文本制品
     * @param artifactSha256 规范化制品 UTF-8 字节的 SHA-256
     * @param artifactContract 产生该规范化制品的稳定合同
     * @param license Corpus 的许可或内部使用声明
     * @param review Source、标签和哈希的可追溯复核记录
     * @param mediaType 标准媒体类型
     * @param expectedElements 应产生的元素类型和角色
     * @param boundaries 必须断开或合并的相邻语义范围
     * @param mustPreserve 必须进入可检索 Chunk 的范围
     * @param mustRemove 必须被清洗或降为非索引元数据的范围
     * @param hardGates 企业验收硬门禁
     */
    public record Case(
            String id,
            String description,
            String sourceName,
            byte[] sourceBytes,
            String sourceSha256,
            String artifactText,
            String artifactSha256,
            String artifactContract,
            String license,
            Review review,
            String mediaType,
            List<ExpectedElement> expectedElements,
            List<BoundaryExpectation> boundaries,
            List<SourceRange> mustPreserve,
            List<SourceRange> mustRemove,
            HardGates hardGates
    ) {

        /** 复制标签并拒绝越界的 Golden Source 范围。 */
        public Case {
            id = required(id, "case id");
            description = required(description, "case description");
            sourceName = required(sourceName, "sourceName");
            sourceBytes = Objects.requireNonNull(sourceBytes, "sourceBytes must not be null")
                    .clone();
            sourceSha256 = sha256(sourceSha256, "sourceSha256");
            artifactText = Objects.requireNonNull(
                    artifactText,
                    "artifactText must not be null"
            );
            artifactSha256 = sha256(artifactSha256, "artifactSha256");
            artifactContract = required(artifactContract, "artifactContract");
            license = required(license, "license");
            review = Objects.requireNonNull(review, "review must not be null");
            mediaType = required(mediaType, "mediaType");
            expectedElements = immutable(expectedElements, "expectedElements");
            boundaries = immutable(boundaries, "boundaries");
            mustPreserve = immutable(mustPreserve, "mustPreserve");
            mustRemove = immutable(mustRemove, "mustRemove");
            hardGates = Objects.requireNonNull(hardGates, "hardGates must not be null");
            validateRanges(artifactText.length(), expectedElements, boundaries,
                    mustPreserve, mustRemove);
        }

        /** 原件可能是可变数组，任何调用方都只能拿到防御性副本。 */
        @Override
        public byte[] sourceBytes() {
            return sourceBytes.clone();
        }
    }

    /**
     * Golden Source 与标签的可追溯复核证据。
     *
     * <p>该记录只证明指定角色在指定日期核对了当前 Fixture，不代表真实 Parser、
     * Chunker 或生产发布门禁已经通过。</p>
     *
     * @param reviewedBy 复核人或受治理的复核角色
     * @param reviewedAt ISO-8601 日期
     */
    public record Review(String reviewedBy, String reviewedAt) {
        /** 拒绝匿名复核和无法排序的日期。 */
        public Review {
            reviewedBy = required(reviewedBy, "reviewedBy");
            reviewedAt = required(reviewedAt, "reviewedAt");
            try {
                LocalDate.parse(reviewedAt);
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException(
                        "reviewedAt must be an ISO-8601 date",
                        failure
                );
            }
        }
    }

    /**
     * 一个结构元素的源范围期望。
     *
     * @param range 元素应覆盖的 Artifact 核心文本，不要求包含 Markdown 装饰符
     * @param type 期望元素类型
     * @param role 可选业务角色，例如 {@code FRONT_MATTER}
     */
    public record ExpectedElement(SourceRange range, ElementType type, String role) {
        /** 元素类型与范围必须明确，角色允许为空。 */
        public ExpectedElement {
            Objects.requireNonNull(range, "element range must not be null");
            Objects.requireNonNull(type, "element type must not be null");
        }
    }

    /**
     * 表示两个源范围最终必须属于同一 Chunk 或不同 Chunk。
     * 标签只描述不随合法 Token 预算变化的结构或语义不变量；预算触发的具体切点由溢出门禁验证，
     * 否则同一份数据集会对不同 Space 的有效配置给出相互矛盾的结论。
     */
    public enum BoundaryRelation {
        /** 不得存在同时覆盖左右范围的 Chunk。 */
        MUST_BREAK,
        /** 必须至少存在一个同时覆盖左右范围的 Chunk。 */
        MUST_JOIN
    }

    /**
     * Chunk 边界标签。
     *
     * @param relation 边界关系
     * @param left 左侧语义锚点范围
     * @param right 右侧语义锚点范围
     */
    public record BoundaryExpectation(
            BoundaryRelation relation,
            SourceRange left,
            SourceRange right
    ) {
        /** 左右范围必须按源文件阅读顺序排列且不重叠。 */
        public BoundaryExpectation {
            Objects.requireNonNull(relation, "boundary relation must not be null");
            Objects.requireNonNull(left, "left range must not be null");
            Objects.requireNonNull(right, "right range must not be null");
            if (left.endOffset() > right.startOffset()) {
                throw new IllegalArgumentException("boundary ranges must be ordered and disjoint");
            }
        }
    }

    /**
     * 企业验收不可用平均值掩盖的单样例硬门禁。
     *
     * @param minimumParseSuccessRate 最低解析成功率，单样例通常为 1.0
     * @param maximumTokenOverflowCount 允许的 Token 超限数
     * @param maximumInvalidSourceSpanCount 允许的非法来源范围数
     * @param minimumSourceAccountingRate 最低语义来源核算率
     * @param maximumSilentTruncationCount 允许的静默截断数
     */
    public record HardGates(
            double minimumParseSuccessRate,
            int maximumTokenOverflowCount,
            int maximumInvalidSourceSpanCount,
            double minimumSourceAccountingRate,
            int maximumSilentTruncationCount
    ) {
        /** 校验门禁阈值均在可解释范围内。 */
        public HardGates {
            if (!Double.isFinite(minimumParseSuccessRate)
                    || minimumParseSuccessRate < 0.0D || minimumParseSuccessRate > 1.0D
                    || maximumTokenOverflowCount < 0 || maximumInvalidSourceSpanCount < 0
                    || !Double.isFinite(minimumSourceAccountingRate)
                    || minimumSourceAccountingRate < 0.0D || minimumSourceAccountingRate > 1.0D
                    || maximumSilentTruncationCount < 0) {
                throw new IllegalArgumentException("invalid extraction hard gate threshold");
            }
        }
    }

    private static void validateRanges(
            int sourceLength,
            List<ExpectedElement> elements,
            List<BoundaryExpectation> boundaries,
            List<SourceRange> preserved,
            List<SourceRange> removed
    ) {
        elements.forEach(value -> requireValid(value.range(), sourceLength));
        boundaries.forEach(value -> {
            requireValid(value.left(), sourceLength);
            requireValid(value.right(), sourceLength);
        });
        preserved.forEach(value -> requireValid(value, sourceLength));
        removed.forEach(value -> requireValid(value, sourceLength));
        for (SourceRange keep : preserved) {
            if (removed.stream().anyMatch(keep::overlaps)) {
                throw new IllegalArgumentException("mustPreserve and mustRemove must not overlap");
            }
        }
    }

    private static void requireValid(SourceRange range, int sourceLength) {
        Objects.requireNonNull(range, "source range must not be null");
        if (!range.isValidFor(sourceLength)) {
            throw new IllegalArgumentException("golden source range is outside source text");
        }
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value, String name) {
        String normalized = required(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }
}
