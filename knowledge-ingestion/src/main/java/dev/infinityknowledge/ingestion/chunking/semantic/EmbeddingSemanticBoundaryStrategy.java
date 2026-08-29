package dev.infinityknowledge.ingestion.chunking.semantic;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.ingestion.chunking.ChunkBoundaryAdvice;
import dev.infinityknowledge.ingestion.chunking.ChunkBoundaryStrategy;
import dev.infinityknowledge.ingestion.chunking.ElementSlice;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用相邻文本的 Embedding 余弦相似度给出双向边界建议。
 *
 * <p>该策略不调用生成式 LLM，也不物化 Chunk。低相似度产生 CUT，高相似度产生
 * JOIN，中间区域保持中立；平台结构规划器最终裁决所有建议。</p>
 */
public final class EmbeddingSemanticBoundaryStrategy implements ChunkBoundaryStrategy {
    /** 当前双向 Embedding 边界算法版本。 */
    public static final String VERSION = "embedding-semantic-boundary-v3";
    /** 输入超过同步语义预算。 */
    public static final String BUDGET_EXCEEDED = "SEMANTIC_REFINEMENT_BUDGET_EXCEEDED";
    /** 向量 Provider 调用失败。 */
    public static final String PROVIDER_FAILED = "SEMANTIC_REFINEMENT_PROVIDER_FAILED";
    /** 模型阶段超过总时限。 */
    public static final String STAGE_TIMEOUT = "SEMANTIC_REFINEMENT_STAGE_TIMEOUT";
    /** 当前线程被取消或模型任务未接收中断。 */
    public static final String STAGE_INTERRUPTED = "SEMANTIC_REFINEMENT_STAGE_INTERRUPTED";
    /** 有界模型执行器已满。 */
    public static final String STAGE_REJECTED = "SEMANTIC_REFINEMENT_STAGE_REJECTED";
    /** Provider 返回数量、索引、维度或数值不符合契约。 */
    public static final String INVALID_EMBEDDING_RESULT =
            "SEMANTIC_REFINEMENT_INVALID_EMBEDDING_RESULT";

    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingSpec embeddingSpec;
    private final double splitSimilarityThreshold;
    private final double mergeSimilarityThreshold;
    private final int contextSlices;
    private final SemanticChunkingBudget budget;
    private final ExecutorService semanticExecutor;

    /**
     * 创建绝对余弦相似度驱动的边界策略。
     *
     * @param splitSimilarityThreshold 小于等于该值时建议 CUT
     * @param mergeSimilarityThreshold 大于等于该值时建议 JOIN
     * @param contextSlices 边界每一侧额外参与成对余弦均值的 Slice 数量
     */
    public EmbeddingSemanticBoundaryStrategy(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            double splitSimilarityThreshold,
            double mergeSimilarityThreshold,
            int contextSlices,
            SemanticChunkingBudget budget,
            ExecutorService semanticExecutor
    ) {
        this.embeddingProvider = Objects.requireNonNull(
                embeddingProvider,
                "embeddingProvider must not be null"
        );
        this.embeddingSpec = Objects.requireNonNull(
                embeddingSpec,
                "embeddingSpec must not be null"
        );
        if (!Double.isFinite(splitSimilarityThreshold)
                || !Double.isFinite(mergeSimilarityThreshold)
                || splitSimilarityThreshold < -1.0D
                || mergeSimilarityThreshold > 1.0D
                || splitSimilarityThreshold >= mergeSimilarityThreshold) {
            throw new IllegalArgumentException(
                    "similarity thresholds must satisfy -1 <= split < merge <= 1"
            );
        }
        if (contextSlices < 0 || contextSlices > 2) {
            throw new IllegalArgumentException("contextSlices must be between 0 and 2");
        }
        this.splitSimilarityThreshold = splitSimilarityThreshold;
        this.mergeSimilarityThreshold = mergeSimilarityThreshold;
        this.contextSlices = contextSlices;
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.semanticExecutor = Objects.requireNonNull(
                semanticExecutor,
                "semanticExecutor must not be null"
        );
    }

    /** 返回部署模型、绝对阈值、上下文窗口和全部硬预算组成的契约。 */
    @Override
    public String contract() {
        return String.join(
                ":",
                VERSION,
                "profile=" + embeddingProfileId(embeddingSpec),
                "splitSimilarity=" + Double.toString(splitSimilarityThreshold),
                "mergeSimilarity=" + Double.toString(mergeSimilarityThreshold),
                "contextSlices=" + contextSlices,
                "budget=" + budget.contract()
        );
    }

    /** 对同章节普通文本的相邻边界进行一次有界批量向量评估。 */
    @Override
    public ChunkBoundaryAdvice advise(List<ElementSlice> slices) {
        slices = List.copyOf(Objects.requireNonNull(slices, "slices must not be null"));
        validateDocumentBudget(slices);
        SemanticPlan plan = plan(slices);
        validatePlanBudget(plan);
        if (plan.candidates().isEmpty()) {
            return ChunkBoundaryAdvice.none();
        }

        List<double[]> normalizedVectors = embedAndValidate(
                plan.inputs(),
                deadlineNanos()
        );
        Set<Integer> cuts = new TreeSet<>();
        Set<Integer> joins = new TreeSet<>();
        int neutral = 0;
        for (BoundaryCandidate candidate : plan.candidates()) {
            double similarity = pairwiseMeanSimilarity(
                    normalizedVectors,
                    candidate.leftWindowStart(),
                    candidate.leftWindowEnd(),
                    candidate.rightWindowStart(),
                    candidate.rightWindowEnd()
            );
            similarity = Math.max(-1.0D, Math.min(1.0D, similarity));
            if (!Double.isFinite(similarity)) {
                throw invalidEmbedding("embedding result produced an invalid cosine similarity");
            }
            if (similarity <= splitSimilarityThreshold) {
                cuts.add(candidate.breakBeforeSliceIndex());
            } else if (similarity >= mergeSimilarityThreshold) {
                joins.add(candidate.breakBeforeSliceIndex());
            } else {
                neutral++;
            }
        }
        return new ChunkBoundaryAdvice(
                cuts,
                joins,
                plan.candidates().size(),
                neutral
        );
    }

    /** 返回配置必须精确匹配的部署级 Embedding Profile 标识。 */
    public static String embeddingProfileId(EmbeddingSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        return spec.providerId() + "/" + spec.modelId() + "@" + spec.dimensions();
    }

    private void validateDocumentBudget(List<ElementSlice> slices) {
        long totalCharacters = 0L;
        Set<java.util.UUID> elements = new java.util.HashSet<>();
        for (ElementSlice slice : slices) {
            elements.add(slice.elementId());
            totalCharacters += slice.content().length();
            if (totalCharacters > budget.maximumTotalCharacters()) {
                throw budgetExceeded("document exceeds maximumTotalCharacters");
            }
        }
        if (elements.size() > budget.maximumElements()) {
            throw budgetExceeded("document exceeds maximumElements");
        }
    }

    private void validatePlanBudget(SemanticPlan plan) {
        if (plan.inputs().size() > budget.maximumEmbeddingInputs()) {
            throw budgetExceeded("document exceeds maximumEmbeddingInputs");
        }
        if (plan.candidates().size() > budget.maximumCandidates()) {
            throw budgetExceeded("document exceeds maximumCandidates");
        }
        long vectorValues = (long) plan.inputs().size() * embeddingSpec.dimensions();
        if (vectorValues > budget.maximumVectorValues()) {
            throw budgetExceeded("embedding result exceeds maximumVectorValues");
        }
        for (String input : plan.inputs()) {
            if (input.length() > budget.maximumInputCharacters()) {
                throw budgetExceeded("semantic input exceeds maximumInputCharacters");
            }
        }
    }

    private SemanticPlan plan(List<ElementSlice> slices) {
        List<List<IndexedSlice>> runs = semanticRuns(slices);
        List<String> inputs = new ArrayList<>();
        List<BoundaryCandidate> candidates = new ArrayList<>();
        for (List<IndexedSlice> run : runs) {
            int inputOffset = inputs.size();
            for (int index = 0; index < run.size(); index++) {
                if (inputs.size() >= budget.maximumEmbeddingInputs()) {
                    throw budgetExceeded("document exceeds maximumEmbeddingInputs");
                }
                // 每个 Slice 只嵌入自身正文，避免相邻窗口共享边界两侧文本而虚高 JOIN。
                String input = run.get(index).slice().content();
                if (input.length() > budget.maximumInputCharacters()) {
                    throw budgetExceeded("semantic input exceeds maximumInputCharacters");
                }
                inputs.add(input);
            }
            int inputEnd = inputOffset + run.size();
            for (int index = 1; index < run.size(); index++) {
                if (candidates.size() >= budget.maximumCandidates()) {
                    throw budgetExceeded("document exceeds maximumCandidates");
                }
                int left = inputOffset + index - 1;
                int right = inputOffset + index;
                candidates.add(new BoundaryCandidate(
                        Math.max(inputOffset, left - contextSlices),
                        left + 1,
                        right,
                        Math.min(inputEnd, right + contextSlices + 1),
                        run.get(index).sliceIndex()
                ));
            }
        }
        return new SemanticPlan(List.copyOf(inputs), List.copyOf(candidates));
    }

    private static List<List<IndexedSlice>> semanticRuns(List<ElementSlice> slices) {
        List<List<IndexedSlice>> runs = new ArrayList<>();
        List<IndexedSlice> current = new ArrayList<>();
        for (int index = 0; index < slices.size(); index++) {
            ElementSlice slice = slices.get(index);
            if (!semanticText(slice.type()) || (slice.hardBoundaryBefore() && !current.isEmpty())) {
                appendRun(runs, current);
                current = new ArrayList<>();
                if (!semanticText(slice.type())) {
                    continue;
                }
            }
            if (!current.isEmpty()
                    && !current.getFirst().slice().sectionPath().equals(slice.sectionPath())) {
                appendRun(runs, current);
                current = new ArrayList<>();
            }
            current.add(new IndexedSlice(index, slice));
        }
        appendRun(runs, current);
        return List.copyOf(runs);
    }

    private static void appendRun(
            List<List<IndexedSlice>> runs,
            List<IndexedSlice> candidate
    ) {
        if (candidate.size() >= 2) {
            runs.add(List.copyOf(candidate));
        }
    }

    private List<double[]> embedAndValidate(List<String> inputs, long deadlineNanos) {
        return validateEmbeddingResult(embedWithinDeadline(inputs, deadlineNanos), inputs.size());
    }

    private List<EmbeddingVector> embedWithinDeadline(
            List<String> inputs,
            long deadlineNanos
    ) {
        final Future<List<EmbeddingVector>> task;
        try {
            task = semanticExecutor.submit(() -> embeddingProvider.embed(inputs, embeddingSpec));
        } catch (RejectedExecutionException rejected) {
            throw new SemanticChunkingException(
                    STAGE_REJECTED,
                    "semantic embedding stage is at capacity",
                    rejected
            );
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            task.cancel(true);
            throw new SemanticChunkingException(
                    STAGE_TIMEOUT,
                    "semantic embedding stage exceeded its deadline"
            );
        }
        try {
            return task.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new SemanticChunkingException(
                    STAGE_TIMEOUT,
                    "semantic embedding stage exceeded its deadline",
                    timeout
            );
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new SemanticChunkingException(
                    STAGE_INTERRUPTED,
                    "semantic embedding stage was interrupted",
                    interrupted
            );
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof SemanticChunkingException semanticFailure) {
                throw semanticFailure;
            }
            throw new SemanticChunkingException(
                    PROVIDER_FAILED,
                    "semantic embedding provider failed",
                    cause == null ? executionFailure : cause
            );
        }
    }

    private List<double[]> validateEmbeddingResult(
            List<EmbeddingVector> vectors,
            int expectedInputCount
    ) {
        if (vectors == null || vectors.size() != expectedInputCount) {
            throw invalidEmbedding("embedding result count differs from input count");
        }
        List<double[]> normalized = new ArrayList<>(vectors.size());
        for (int index = 0; index < vectors.size(); index++) {
            EmbeddingVector vector = vectors.get(index);
            if (vector == null || vector.index() != index) {
                throw invalidEmbedding("embedding result index differs from input order");
            }
            if (vector.values().size() != embeddingSpec.dimensions()) {
                throw invalidEmbedding("embedding result dimension differs from specification");
            }
            normalized.add(normalize(vector));
        }
        return List.copyOf(normalized);
    }

    private long deadlineNanos() {
        try {
            return Math.addExact(System.nanoTime(), budget.stageTimeout().toNanos());
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("semantic stage timeout is too large", overflow);
        }
    }

    private static double[] normalize(EmbeddingVector vector) {
        double norm = 0.0D;
        for (Double value : vector.values()) {
            if (value == null || !Double.isFinite(value)) {
                throw invalidEmbedding("embedding result contains a non-finite value");
            }
            norm = Math.hypot(norm, value);
        }
        if (!Double.isFinite(norm) || norm == 0.0D) {
            throw invalidEmbedding("embedding result contains a zero or invalid vector");
        }
        double[] normalized = new double[vector.values().size()];
        for (int index = 0; index < normalized.length; index++) {
            normalized[index] = vector.values().get(index) / norm;
        }
        return normalized;
    }

    private static double dot(double[] left, double[] right) {
        double result = 0.0D;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }

    /**
     * 对左右不重叠窗口的所有向量对求余弦均值。
     *
     * <p>合法的相反向量可能在向量均值中完全相消；直接计算 cross-pair 均值既不会
     * 产生伪造的零向量错误，也始终保留在 [-1, 1]。</p>
     */
    private static double pairwiseMeanSimilarity(
            List<double[]> vectors,
            int leftStartInclusive,
            int leftEndExclusive,
            int rightStartInclusive,
            int rightEndExclusive
    ) {
        if (leftStartInclusive < 0 || leftEndExclusive <= leftStartInclusive
                || rightStartInclusive < leftEndExclusive
                || rightEndExclusive <= rightStartInclusive
                || rightEndExclusive > vectors.size()) {
            throw new IllegalArgumentException("semantic vector window is invalid");
        }
        double sum = 0.0D;
        int pairs = 0;
        for (int left = leftStartInclusive; left < leftEndExclusive; left++) {
            for (int right = rightStartInclusive; right < rightEndExclusive; right++) {
                sum += dot(vectors.get(left), vectors.get(right));
                pairs++;
            }
        }
        return sum / pairs;
    }

    private SemanticChunkingException budgetExceeded(String message) {
        return new SemanticChunkingException(BUDGET_EXCEEDED, message);
    }

    private static SemanticChunkingException invalidEmbedding(String message) {
        return new SemanticChunkingException(INVALID_EMBEDDING_RESULT, message);
    }

    private static boolean semanticText(ElementType type) {
        return type == ElementType.PARAGRAPH || type == ElementType.LIST;
    }

    private record SemanticPlan(
            List<String> inputs,
            List<BoundaryCandidate> candidates
    ) {
    }

    private record IndexedSlice(int sliceIndex, ElementSlice slice) {
    }

    private record BoundaryCandidate(
            int leftWindowStart,
            int leftWindowEnd,
            int rightWindowStart,
            int rightWindowEnd,
            int breakBeforeSliceIndex
    ) {
    }
}
