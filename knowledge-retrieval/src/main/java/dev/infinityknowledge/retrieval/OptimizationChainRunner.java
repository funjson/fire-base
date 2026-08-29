package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.ChainNode;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.retrieval.fusion.FusedCandidate;
import dev.infinityknowledge.retrieval.query.ResolvedConstraints;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 按系统固定枚举顺序选择下一个满足前置条件的 Optimization Chain 节点。
 *
 * <p>Space 只能开关节点；Runner 不接受用户顺序，也不调用模型选择策略。</p>
 */
final class OptimizationChainRunner {

    /** 从头扫描尚未完成的节点，并返回首个满足系统前置条件的节点。 */
    Optional<ChainNode> next(Context context, List<RetrievalStepTrace> steps) {
        return next(context, steps, ignored -> { });
    }

    /** 从头扫描节点，并把每个真实执行的适用性判断交给旁路观测。 */
    Optional<ChainNode> next(
            Context context,
            List<RetrievalStepTrace> steps,
            Consumer<Evaluation> observer
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        for (ChainNode node : ChainNode.values()) {
            if (!context.configuration().chainNodeEnables().get(node)
                    || context.completedOrUnavailableNodes().contains(node)) {
                continue;
            }
            Evaluation evaluation = evaluate(node, context);
            boolean applicable = evaluation.applicable();
            steps.add(new RetrievalStepTrace(
                    "CHAIN_NODE_" + node.name(),
                    Duration.ZERO,
                    1,
                    applicable ? 1 : 0,
                    applicable ? "APPLICABLE" : "SKIPPED"
            ));
            observer.accept(evaluation);
            if (applicable) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private Evaluation evaluate(ChainNode node, Context context) {
        if (context.coverage() >= context.configuration().coverage()
                .sufficiencyThreshold()) {
            return new Evaluation(node, false, "COVERAGE_SUFFICIENT");
        }
        return switch (node) {
            case GAP_QUERY -> evaluation(
                    node,
                    !context.missingGaps().isEmpty(),
                    "NO_MISSING_GAPS"
            );
            case PRF -> evaluation(
                    node,
                    context.evidenceMemory().stream().anyMatch(value ->
                            context.currentSpace().equals(value.representative().spaceId())),
                    "NO_CURRENT_SPACE_EVIDENCE"
            );
            case RELAX_CONSTRAINTS -> evaluation(
                    node,
                    context.resolvedConstraints().canRelax(),
                    "NO_RELAXABLE_CONSTRAINT"
            );
            case NARROW_CONSTRAINTS -> evaluation(
                    node,
                    context.resolvedConstraints().canNarrow(),
                    "NO_NARROWING_CONSTRAINT"
            );
            case STEP_BACK -> new Evaluation(node, true, "APPLICABLE");
            case HYDE -> evaluation(
                    node,
                    context.configuration().branches().channels()
                            .get(dev.infinityknowledge.domain.retrieval.RetrievalChannel.VECTOR)
                            .enabled(),
                    "VECTOR_BRANCH_DISABLED"
            );
            case NEXT_SPACE -> evaluation(
                    node,
                    context.configuration().crossSpace().enabled()
                            && context.currentSpaceIndex() + 1
                                    < context.orderedSpaces().size()
                            && context.visitedSpaceCount()
                                    < context.configuration().crossSpace().maximumSpaces(),
                    "NO_NEXT_SPACE"
            );
        };
    }

    private Evaluation evaluation(ChainNode node, boolean applicable, String skippedReason) {
        return new Evaluation(node, applicable, applicable ? "APPLICABLE" : skippedReason);
    }

    /** 一个固定节点的适用性事实和稳定原因。 */
    record Evaluation(ChainNode node, boolean applicable, String reasonCode) {
        Evaluation {
            Objects.requireNonNull(node, "node must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        }
    }

    /** 固定前置条件需要的最小运行上下文。 */
    record Context(
            RetrievalConfiguration configuration,
            KnowledgeSpaceId currentSpace,
            int currentSpaceIndex,
            List<KnowledgeSpaceId> orderedSpaces,
            int visitedSpaceCount,
            double coverage,
            List<String> missingGaps,
            List<FusedCandidate> evidenceMemory,
            ResolvedConstraints resolvedConstraints,
            Set<ChainNode> completedOrUnavailableNodes
    ) {
        Context {
            Objects.requireNonNull(configuration, "configuration must not be null");
            Objects.requireNonNull(currentSpace, "currentSpace must not be null");
            orderedSpaces = List.copyOf(orderedSpaces);
            missingGaps = List.copyOf(missingGaps);
            evidenceMemory = List.copyOf(evidenceMemory);
            Objects.requireNonNull(
                    resolvedConstraints,
                    "resolvedConstraints must not be null"
            );
            completedOrUnavailableNodes = Set.copyOf(completedOrUnavailableNodes);
        }
    }
}
