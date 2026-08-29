package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import dev.infinityknowledge.ingestion.chunking.StructuralPlanBuilder.Plan;
import dev.infinityknowledge.ingestion.chunking.StructuralPlanBuilder.PlannedChunk;

/**
 * 将平台计划统一物化为稳定 KnowledgeChunk，并在允许的边界上添加精确 Overlap。
 */
final class ChunkAssembler {
    static final String VERSION = "chunk-assembler-v2";

    Assembly assemble(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            Plan plan,
            ChunkSizing sizing,
            String providerId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(sizing, "sizing must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");

        List<KnowledgeChunk> chunks = new ArrayList<>(plan.chunks().size());
        List<Integer> units = new ArrayList<>(plan.chunks().size());
        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            PlannedChunk planned = plan.chunks().get(ordinal);
            List<ElementSlice> materializedSlices = materializedSlices(
                    plan.chunks(),
                    ordinal,
                    sizing
            );
            String content = ElementSlice.joinContent(materializedSlices);
            String contextualText = ElementSlice.contextualText(materializedSlices);
            int chunkUnits = sizing.tokenCounter().count(contextualText);
            if (chunkUnits > sizing.maximumTokens()) {
                throw new IllegalStateException(
                        "assembled chunk contextualText exceeds maximumTokens"
                );
            }
            if (materializedSlices.size()
                    > StructuralPlanBuilder.MAXIMUM_SOURCE_SPANS_PER_CHUNK) {
                throw new IllegalStateException("assembled chunk exceeds source span limit");
            }
            units.add(chunkUnits);
            String contentHash = IngestionIdentity.sha256(content);
            UUID chunkId = UUID.nameUUIDFromBytes(
                    (revisionId + ":" + ordinal + ":" + contentHash)
                            .getBytes(StandardCharsets.UTF_8)
            );
            List<UUID> elementIds = new ArrayList<>(new LinkedHashSet<>(
                    materializedSlices.stream().map(ElementSlice::elementId).toList()
            ));
            List<ChunkSourceSpan> sourceSpans = materializedSlices.stream()
                    .map(ElementSlice::sourceSpan)
                    .toList();
            List<String> sectionPath = planned.slices().getFirst().sectionPath();
            Map<String, String> metadata = Map.of(
                    "chunker", VERSION,
                    "boundaryProvider", providerId,
                    "tokenCounter", sizing.tokenCounter().id()
            );
            chunks.add(new KnowledgeChunk(
                    chunkId,
                    tenantId,
                    spaceId,
                    documentId,
                    revisionId,
                    elementIds,
                    sourceSpans,
                    ordinal,
                    sectionPath,
                    content,
                    contextualText,
                    contentHash,
                    metadata
            ));
        }
        if (units.isEmpty()) {
            return new Assembly(List.of(), 0, 0.0D, 0);
        }
        int minimum = units.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int maximum = units.stream().mapToInt(Integer::intValue).max().orElseThrow();
        double average = units.stream().mapToInt(Integer::intValue).average().orElseThrow();
        return new Assembly(chunks, minimum, average, maximum);
    }

    private static List<ElementSlice> materializedSlices(
            List<PlannedChunk> plans,
            int index,
            ChunkSizing sizing
    ) {
        PlannedChunk current = plans.get(index);
        if (index == 0 || sizing.overlapTokens() == 0
                || current.overlapBarrierBefore()) {
            return current.slices();
        }
        List<ElementSlice> overlap = overlapSuffix(
                plans.get(index - 1).slices(),
                current.slices(),
                sizing
        );
        if (overlap.isEmpty()) {
            return current.slices();
        }
        List<ElementSlice> combined = new ArrayList<>(
                overlap.size() + current.slices().size()
        );
        combined.addAll(overlap);
        combined.addAll(current.slices());
        return List.copyOf(combined);
    }

    /**
     * 从上一基础计划选择最长安全后缀；只复制 Slice 或其连续尾部，引用偏移保持原值。
     */
    private static List<ElementSlice> overlapSuffix(
            List<ElementSlice> previous,
            List<ElementSlice> current,
            ChunkSizing sizing
    ) {
        int availableSpans = StructuralPlanBuilder.MAXIMUM_SOURCE_SPANS_PER_CHUNK
                - current.size();
        if (availableSpans <= 0) {
            return List.of();
        }
        List<ElementSlice> selected = new ArrayList<>();
        for (int index = previous.size() - 1;
                index >= 0 && selected.size() < availableSpans;
                index--) {
            ElementSlice slice = previous.get(index);
            List<ElementSlice> wholeCandidate = prepend(slice, selected);
            if (fitsOverlap(wholeCandidate, current, sizing)) {
                selected = new ArrayList<>(wholeCandidate);
                continue;
            }
            // Overlap 只复制完整 Slice，避免在任意 Code Point 处截断单词或语句。
            break;
        }
        return List.copyOf(selected);
    }

    private static boolean fitsOverlap(
            List<ElementSlice> overlap,
            List<ElementSlice> current,
            ChunkSizing sizing
    ) {
        if (overlap.isEmpty()) {
            return true;
        }
        if (sizing.tokenCounter().count(ElementSlice.contextualText(overlap))
                > sizing.overlapTokens()) {
            return false;
        }
        List<ElementSlice> combined = new ArrayList<>(overlap.size() + current.size());
        combined.addAll(overlap);
        combined.addAll(current);
        return sizing.tokenCounter().count(ElementSlice.contextualText(combined))
                <= sizing.maximumTokens();
    }

    private static List<ElementSlice> prepend(
            ElementSlice slice,
            List<ElementSlice> existing
    ) {
        List<ElementSlice> result = new ArrayList<>(existing.size() + 1);
        result.add(slice);
        result.addAll(existing);
        return List.copyOf(result);
    }

    /** 统一物化结果及最终检索文本的大小分布。 */
    record Assembly(
            List<KnowledgeChunk> chunks,
            int minimumUnits,
            double averageUnits,
            int maximumUnits
    ) {
        Assembly {
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        }
    }
}
