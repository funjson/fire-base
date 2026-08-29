package dev.infinityknowledge.evaluation.extraction;

/**
 * 规范化文档 Artifact 中的 UTF-16 文本范围，起点包含、终点不包含。
 *
 * <p>验收标签使用源范围而不是 Chunk 标识。Chunk 标识会随策略版本变化，
 * Artifact 范围却能在同一份固定合同和 Golden 制品上稳定表达“这里必须断开或
 * 保留”。Parser 或 Artifact 合同变化时必须升级 Dataset，不能跨坐标空间复用。</p>
 *
 * @param startOffset Artifact 文本起始偏移
 * @param endOffset Artifact 文本结束偏移
 */
public record SourceRange(int startOffset, int endOffset) {

    /** 判断当前范围是否是给定源文本内的非空合法范围。 */
    public boolean isValidFor(int sourceLength) {
        return startOffset >= 0 && endOffset > startOffset && endOffset <= sourceLength;
    }

    /** 判断两个半开区间是否存在交集。 */
    public boolean overlaps(SourceRange other) {
        return startOffset < other.endOffset && other.startOffset < endOffset;
    }
}
