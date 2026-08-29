package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 平台托管的唯一 Chunk 执行链路。
 *
 * <p>Provider 只能产生边界建议；ElementSlice、结构硬约束、大小规划、Overlap、
 * SourceSpan 和 KnowledgeChunk 标识始终由平台组件统一处理。</p>
 */
final class ManagedKnowledgeChunker implements KnowledgeChunker {
    private static final String VERSION = "managed-chunker-v2";

    private final String providerId;
    private final String providerVersion;
    private final ChunkBoundaryStrategy boundaryStrategy;
    private final ChunkSizing sizing;
    private final StructuralPlanBuilder planBuilder;
    private final ChunkAssembler assembler;
    private final String contract;

    ManagedKnowledgeChunker(
            String providerId,
            String providerVersion,
            ChunkBoundaryStrategy boundaryStrategy,
            ChunkSizing sizing
    ) {
        this.providerId = requiredText(providerId, "providerId");
        this.providerVersion = requiredText(providerVersion, "providerVersion");
        this.boundaryStrategy = Objects.requireNonNull(
                boundaryStrategy,
                "boundaryStrategy must not be null"
        );
        this.sizing = Objects.requireNonNull(sizing, "sizing must not be null");
        planBuilder = new StructuralPlanBuilder();
        assembler = new ChunkAssembler();
        contract = String.join(
                ":",
                VERSION,
                "provider=" + this.providerId,
                "providerVersion=" + this.providerVersion,
                "strategy=" + requiredText(boundaryStrategy.contract(), "strategyContract"),
                "sizing=" + sizing.contract(),
                "slicer=" + ElementSlicer.VERSION,
                "planner=" + StructuralPlanBuilder.VERSION,
                "assembler=" + ChunkAssembler.VERSION,
                "spans=" + StructuralPlanBuilder.MAXIMUM_SOURCE_SPANS_PER_CHUNK
        );
    }

    @Override
    public String contract() {
        return contract;
    }

    @Override
    public ChunkingResult chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            List<KnowledgeElement> elements
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        List<ElementSlice> slices = ElementSlicer.slice(revisionId, elements, sizing);
        ChunkBoundaryAdvice advice = Objects.requireNonNull(
                boundaryStrategy.advise(slices),
                "boundary strategy returned null advice"
        );
        StructuralPlanBuilder.Plan plan = planBuilder.plan(slices, advice, sizing);
        ChunkAssembler.Assembly assembly = assembler.assemble(
                tenantId,
                spaceId,
                documentId,
                revisionId,
                plan,
                sizing,
                providerId
        );
        ChunkingDiagnostics diagnostics = new ChunkingDiagnostics(
                plan.structuralHardBreaks(),
                plan.baselineSoftBreaks(),
                plan.semanticCandidateBoundaries(),
                plan.semanticCutSuggestions(),
                plan.semanticJoinSuggestions(),
                plan.semanticNeutralSuggestions(),
                plan.semanticCutsAdded(),
                plan.semanticJoinsApplied(),
                plan.semanticNoOps(),
                plan.tokenLimitBreaks(),
                plan.spanLimitBreaks(),
                plan.rejectedSemanticJoins(),
                assembly.chunks().size(),
                assembly.minimumUnits(),
                assembly.averageUnits(),
                assembly.maximumUnits()
        );
        return new ChunkingResult(assembly.chunks(), diagnostics);
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
