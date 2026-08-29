package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将逐层原始事件和 execution 投影为不依赖 Gold 标注的版本化运行事实。
 *
 * <p>指标计算的唯一权威位于 evaluation：存储适配器只保存和聚合这里生成的事实，
 * 不允许再次解释事件 payload。逐事件事实是追加型，execution 事实是可替换快照；
 * 所有比例都保存分子和分母，缺少前置事实时不生成分母，更不会用零冒充缺失值。</p>
 */
public final class RuntimeMetricFactProjector {
    public static final int METRIC_DEFINITION_VERSION = 4;

    /** 投影一条层事件；sourceEventId、指标版本和维度共同保证事实幂等。 */
    public List<MetricFact.Runtime> project(RetrievalObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        MetricDimensions dimensions = eventDimensions(observation);
        List<MetricFact.Runtime> facts = new ArrayList<>();
        facts.add(fact(observation, "retrieval.stage.event.count",
                MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        facts.add(fact(observation, "retrieval.stage.duration.ms",
                MetricFact.Aggregation.DISTRIBUTION,
                observation.duration().toMillis(), dimensions));
        facts.add(fact(observation, "retrieval.stage.input.count",
                MetricFact.Aggregation.DISTRIBUTION,
                observation.payload().inputCount(), dimensions));
        facts.add(fact(observation, "retrieval.stage.output.count",
                MetricFact.Aggregation.DISTRIBUTION,
                observation.payload().outputCount(), dimensions));
        if (observation.stage() != RetrievalObservationStage.EXECUTION_STARTED) {
            addBooleanRatio(observation, "retrieval.stage.normal_completion.rate",
                    successful(observation.status()), dimensions, facts);
        }
        addPayloadFacts(observation, dimensions, facts);
        return List.copyOf(facts);
    }

    /**
     * 投影一个 execution 唯一的可替换指标快照。
     *
     * <p>请求、终态观测和完整性的分母从第一条事件起就存在。技术成功、降级和
     * 端到端耗时只有收到真实终态后才生成；“首轮”指标还要求事件序列完整，防止把
     * 中间缺失后看到的 Coverage 误称为首轮。</p>
     */
    public List<MetricFact.Runtime> projectExecution(
            RetrievalExecutionObservation execution
    ) {
        Objects.requireNonNull(execution, "execution must not be null");
        RetrievalObservation source = execution.terminalEvent()
                .orElseGet(() -> execution.events().getLast());
        MetricDimensions dimensions = executionDimensions(execution, source);
        List<MetricFact.Runtime> facts = new ArrayList<>();
        facts.add(fact(source, "retrieval.request.count",
                MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        facts.add(fact(source, "retrieval.observation.event.count",
                MetricFact.Aggregation.DISTRIBUTION,
                execution.events().size(), dimensions));
        addBooleanRatio(source, "retrieval.observation.complete.rate",
                execution.completeness() == ObservationCompleteness.COMPLETE,
                dimensions, facts);
        addBooleanRatio(source, "retrieval.request.terminal_observed.rate",
                execution.terminalEvent().isPresent(), dimensions, facts);
        execution.terminalEvent().ifPresent(terminal -> addTerminalExecutionFacts(
                execution, terminal, dimensions, facts));
        return List.copyOf(facts);
    }

    private static void addTerminalExecutionFacts(
            RetrievalExecutionObservation execution,
            RetrievalObservation terminal,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        var payload = (RetrievalObservationPayload.ExecutionTerminal) terminal.payload();
        facts.add(fact(terminal, "retrieval.request.terminal.count",
                MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        addBooleanRatio(terminal, "retrieval.request.technical_success.rate",
                successful(terminal.status()), dimensions, facts);
        addBooleanRatio(terminal, "retrieval.request.degraded.rate",
                payload.degraded(), dimensions, facts);
        facts.add(fact(terminal, "retrieval.request.result.count",
                MetricFact.Aggregation.DISTRIBUTION,
                payload.resultCount(), dimensions));
        facts.add(fact(terminal, "retrieval.request.attempt.count",
                MetricFact.Aggregation.DISTRIBUTION,
                payload.retrievalAttemptCount(), dimensions));
        execution.startedEvent()
                .filter(started -> started.stage()
                        == RetrievalObservationStage.EXECUTION_STARTED)
                .ifPresent(started -> addEndToEndDuration(
                        started, terminal, dimensions, facts));
        addFinalSufficiency(terminal, payload, dimensions, facts);
        if (execution.completeness() == ObservationCompleteness.COMPLETE) {
            firstCoverageStatus(execution).ifPresent(first -> addFirstCoverageFacts(
                    terminal, payload, first, dimensions, facts));
        }
    }

    private static void addEndToEndDuration(
            RetrievalObservation started,
            RetrievalObservation terminal,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        Duration duration = Duration.between(started.startedAt(), terminal.completedAt());
        if (duration.isNegative()) {
            throw new IllegalArgumentException(
                    "terminal completedAt must not be before execution startedAt"
            );
        }
        facts.add(fact(terminal, "retrieval.request.duration.ms",
                MetricFact.Aggregation.DISTRIBUTION, duration.toMillis(), dimensions));
    }

    private static void addFinalSufficiency(
            RetrievalObservation terminal,
            RetrievalObservationPayload.ExecutionTerminal payload,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        if (sufficiencyEvaluated(payload.terminalStatus())) {
            addBooleanRatio(terminal, "retrieval.coverage.final_sufficient.rate",
                    payload.terminalStatus() == RetrievalTerminalStatus.SUFFICIENT,
                    dimensions, facts);
        }
    }

    private static void addFirstCoverageFacts(
            RetrievalObservation terminal,
            RetrievalObservationPayload.ExecutionTerminal payload,
            RetrievalObservationPayload.CoverageTerminalStatus first,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        addBooleanRatio(terminal, "retrieval.coverage.first_sufficient.rate",
                first == RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT,
                dimensions, facts);
        if (!sufficiencyEvaluated(payload.terminalStatus())) {
            return;
        }
        boolean initiallyInsufficient = first
                == RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE
                || first == RetrievalObservationPayload.CoverageTerminalStatus.INSUFFICIENT;
        if (initiallyInsufficient) {
            addBooleanRatio(terminal, "retrieval.coverage.recovery.rate",
                    payload.terminalStatus() == RetrievalTerminalStatus.SUFFICIENT,
                    dimensions, facts);
        }
        if (first == RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE) {
            addBooleanRatio(terminal, "retrieval.optimization.budget_exhausted.rate",
                    payload.stopReason()
                            == RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED,
                    dimensions, facts);
        }
    }

    private static Optional<RetrievalObservationPayload.CoverageTerminalStatus>
            firstCoverageStatus(RetrievalExecutionObservation execution) {
        return execution.events().stream()
                .filter(event -> event.payload()
                        instanceof RetrievalObservationPayload.CoverageCheckCompleted)
                .sorted(Comparator.comparingLong(RetrievalObservation::sequence))
                .map(event -> ((RetrievalObservationPayload.CoverageCheckCompleted)
                        event.payload()).terminalStatus())
                .filter(RuntimeMetricFactProjector::isSufficiencyObservation)
                .findFirst();
    }

    private static boolean isSufficiencyObservation(
            RetrievalObservationPayload.CoverageTerminalStatus status
    ) {
        return status == RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT
                || status == RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE
                || status == RetrievalObservationPayload.CoverageTerminalStatus.INSUFFICIENT;
    }

    private static boolean sufficiencyEvaluated(RetrievalTerminalStatus status) {
        return status == RetrievalTerminalStatus.SUFFICIENT
                || status == RetrievalTerminalStatus.INSUFFICIENT;
    }

    /** 只把强类型载荷已经明确给出的事实转成指标。 */
    private static void addPayloadFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        RetrievalObservationPayload payload = observation.payload();
        if (payload instanceof RetrievalObservationPayload.RetrievalBranchCompleted branch) {
            addBranchFacts(observation, dimensions, branch, facts);
        } else if (payload instanceof RetrievalObservationPayload.FusionCompleted fusion) {
            addFusionFacts(observation, dimensions, fusion, facts);
        } else if (payload instanceof RetrievalObservationPayload.RerankCompleted rerank) {
            addRerankFacts(observation, dimensions, rerank, facts);
        } else if (payload instanceof RetrievalObservationPayload.CoverageCheckCompleted coverage) {
            addCoverageFacts(observation, dimensions, coverage, facts);
        } else if (payload instanceof RetrievalObservationPayload.ChainNodeEvaluated node) {
            addChainEvaluationFacts(observation, dimensions, node, facts);
        } else if (payload instanceof RetrievalObservationPayload.ChainNodeCompleted node) {
            addChainCompletionFacts(observation, dimensions, node, facts);
        } else if (payload instanceof RetrievalObservationPayload.SpaceChanged) {
            facts.add(fact(observation, "retrieval.space.changed.count",
                    MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        } else if (payload instanceof RetrievalObservationPayload.QueryPlanningCompleted planning) {
            addUsageFacts(observation, dimensions, "retrieval.query_planning",
                    planning.usage(), facts);
        }
    }

    private static void addBranchFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.RetrievalBranchCompleted branch,
            List<MetricFact.Runtime> facts
    ) {
        boolean completed = successful(observation.status());
        addBooleanRatio(observation, "retrieval.branch.success.rate",
                completed, dimensions, facts);
        if (completed) {
            addBooleanRatio(observation, "retrieval.branch.empty.rate",
                    branch.candidates().isEmpty(), dimensions, facts);
        }
        facts.add(fact(observation, "retrieval.branch.candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                branch.candidates().size(), dimensions));
    }

    private static void addFusionFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.FusionCompleted fusion,
            List<MetricFact.Runtime> facts
    ) {
        facts.add(fact(observation, "retrieval.fusion.input_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                fusion.inputCandidateCount(), dimensions));
        facts.add(fact(observation, "retrieval.fusion.unique_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                fusion.uniqueCandidateCount(), dimensions));
        if (fusion.inputCandidateCount() > 0) {
            addCountRatio(observation, "retrieval.fusion.duplicate.rate",
                    fusion.inputCandidateCount() - fusion.uniqueCandidateCount(),
                    fusion.inputCandidateCount(), dimensions, facts);
        }
    }

    private static void addRerankFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.RerankCompleted rerank,
            List<MetricFact.Runtime> facts
    ) {
        addBooleanRatio(observation, "retrieval.rerank.executed.rate",
                rerank.executed(), dimensions, facts);
        addBooleanRatio(observation, "retrieval.rerank.fallback.rate",
                rerank.fallback(), dimensions, facts);
        facts.add(fact(observation, "retrieval.rerank.input_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                rerank.inputCandidateCount(), dimensions));
        facts.add(fact(observation, "retrieval.rerank.output_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                rerank.candidates().size(), dimensions));
        addUsageFacts(observation, dimensions, "retrieval.rerank", rerank.usage(), facts);
    }

    private static void addCoverageFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.CoverageCheckCompleted coverage,
            List<MetricFact.Runtime> facts
    ) {
        facts.add(fact(observation, "retrieval.coverage.check.count",
                MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        facts.add(fact(observation, "retrieval.coverage.evaluated_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                coverage.evaluatedCandidateCount(), dimensions));
        facts.add(fact(observation, "retrieval.coverage.retained_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                coverage.retainedCandidateCount(), dimensions));
        facts.add(fact(observation, "retrieval.coverage.requirement.count",
                MetricFact.Aggregation.DISTRIBUTION,
                coverage.requirementCount(), dimensions));
        if (isSufficiencyObservation(coverage.terminalStatus())) {
            addBooleanRatio(observation, "retrieval.coverage.sufficient.rate",
                    coverage.terminalStatus()
                            == RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT,
                    dimensions, facts);
        }
        if (coverage.coveredRequirementCount().present()) {
            facts.add(fact(observation, "retrieval.coverage.covered_requirement.count",
                    MetricFact.Aggregation.DISTRIBUTION,
                    coverage.coveredRequirementCount().value(), dimensions));
            if (coverage.requirementCount() > 0) {
                addCountRatio(observation, "retrieval.coverage.requirement_covered.rate",
                        coverage.coveredRequirementCount().value(),
                        coverage.requirementCount(), dimensions, facts);
            }
        }
        if (coverage.evaluatedCandidateCount() > 0) {
            addCountRatio(observation, "retrieval.coverage.candidate_retained.rate",
                    coverage.retainedCandidateCount(),
                    coverage.evaluatedCandidateCount(), dimensions, facts);
        }
        if (coverage.coverageScore().present()) {
            facts.add(fact(observation, "retrieval.coverage.score",
                    MetricFact.Aggregation.DISTRIBUTION,
                    coverage.coverageScore().value(), dimensions));
        }
        if (coverage.threshold().present()) {
            facts.add(fact(observation, "retrieval.coverage.threshold",
                    MetricFact.Aggregation.DISTRIBUTION,
                    coverage.threshold().value(), dimensions));
        }
        addUsageFacts(observation, dimensions, "retrieval.coverage",
                coverage.usage(), facts);
    }

    private static void addChainEvaluationFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.ChainNodeEvaluated node,
            List<MetricFact.Runtime> facts
    ) {
        facts.add(fact(observation, "retrieval.chain.node.evaluated.count",
                MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        addBooleanRatio(observation, "retrieval.chain.node.applicable.rate",
                node.applicable(), dimensions, facts);
        facts.add(fact(observation, "retrieval.chain.node.remaining_attempt.count",
                MetricFact.Aggregation.DISTRIBUTION,
                node.remainingAttemptCount(), dimensions));
    }

    private static void addChainCompletionFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.ChainNodeCompleted node,
            List<MetricFact.Runtime> facts
    ) {
        facts.add(fact(observation, "retrieval.chain.node.executed.count",
                MetricFact.Aggregation.COUNT, 1.0D, dimensions));
        facts.add(fact(observation, "retrieval.chain.node.input_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                node.inputCandidateCount(), dimensions));
        facts.add(fact(observation, "retrieval.chain.node.output_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                node.outputCandidateCount(), dimensions));
        if (node.coverageBefore().present() && node.coverageAfter().present()) {
            double coverageDelta = node.coverageAfter().value()
                    - node.coverageBefore().value();
            facts.add(fact(observation, "retrieval.chain.node.coverage.delta",
                    MetricFact.Aggregation.DISTRIBUTION,
                    coverageDelta, dimensions));
            addBooleanRatio(
                    observation,
                    "retrieval.chain.node.positive_coverage_gain.rate",
                    coverageDelta > 0.0D,
                    dimensions,
                    facts
            );
        }
        addUsageFacts(observation, dimensions, "retrieval.chain.node",
                node.usage(), facts);
    }

    /** 模型调用数始终可观测；Provider 未给 Token 时不生成 Token 指标。 */
    private static void addUsageFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            String prefix,
            RetrievalObservationPayload.UsageCount usage,
            List<MetricFact.Runtime> facts
    ) {
        facts.add(fact(observation, prefix + ".model_request.count",
                MetricFact.Aggregation.SUM, usage.requestCount(), dimensions));
        if (!usage.tokenCountsAvailable()) {
            return;
        }
        facts.add(fact(observation, prefix + ".model_input_token.count",
                MetricFact.Aggregation.SUM, usage.inputTokenCount(), dimensions));
        facts.add(fact(observation, prefix + ".model_output_token.count",
                MetricFact.Aggregation.SUM, usage.outputTokenCount(), dimensions));
    }

    private static void addBooleanRatio(
            RetrievalObservation observation,
            String metricKey,
            boolean matched,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        addCountRatio(observation, metricKey, matched ? 1.0D : 0.0D, 1.0D,
                dimensions, facts);
    }

    private static void addCountRatio(
            RetrievalObservation observation,
            String metricKey,
            double numerator,
            double denominator,
            MetricDimensions dimensions,
            List<MetricFact.Runtime> facts
    ) {
        if (denominator <= 0.0D) {
            throw new IllegalArgumentException("ratio denominator must be positive");
        }
        if (numerator < 0.0D || numerator > denominator) {
            throw new IllegalArgumentException(
                    "ratio numerator must be between zero and denominator"
            );
        }
        facts.add(fact(observation, metricKey,
                MetricFact.Aggregation.RATIO_NUMERATOR, numerator, dimensions));
        facts.add(fact(observation, metricKey,
                MetricFact.Aggregation.RATIO_DENOMINATOR, denominator, dimensions));
    }

    private static boolean successful(RetrievalObservationStatus status) {
        return status == RetrievalObservationStatus.SUCCEEDED
                || status == RetrievalObservationStatus.DEGRADED;
    }

    private static MetricFact.Runtime fact(
            RetrievalObservation observation,
            String metricKey,
            MetricFact.Aggregation aggregation,
            double value,
            MetricDimensions dimensions
    ) {
        return new MetricFact.Runtime(
                observation.eventId(), observation.executionId(), observation.tenantId(),
                metricKey, METRIC_DEFINITION_VERSION, aggregation, value, dimensions,
                observation.completedAt()
        );
    }

    private static MetricDimensions eventDimensions(RetrievalObservation observation) {
        MetricDimensions.Builder builder = MetricDimensions.builder()
                .config(observation.configFingerprint())
                .attempt(observation.attemptIndex())
                .visitIndex(observation.visitIndex())
                .stage(observation.stage())
                .status(observation.status())
                .purpose(observation.purpose())
                .timeSlice(observation.completedAt(),
                        MetricDimensions.TimeSliceGranularity.HOUR);
        if (observation.spaceIds().size() == 1) {
            builder.space(observation.spaceIds().iterator().next());
        }
        if (observation.payload() instanceof RetrievalObservationPayload.ExecutionTerminal value) {
            builder.terminalStatus(value.terminalStatus()).stopReason(value.stopReason());
        }
        addPayloadDimensions(builder, observation.payload());
        return builder.build();
    }

    /** execution 事实使用开始时间归入窗口，终态缺失时明确标记为尚未观测。 */
    private static MetricDimensions executionDimensions(
            RetrievalExecutionObservation execution,
            RetrievalObservation source
    ) {
        Instant cohortAt = execution.startedEvent()
                .map(RetrievalObservation::startedAt)
                .orElseGet(() -> execution.events().getFirst().startedAt());
        MetricDimensions.Builder builder = MetricDimensions.builder()
                .config(source.configFingerprint())
                .purpose(execution.purpose())
                .timeSlice(cohortAt, MetricDimensions.TimeSliceGranularity.HOUR);
        if (source.spaceIds().size() == 1) {
            builder.space(source.spaceIds().iterator().next());
        }
        if (source.payload() instanceof RetrievalObservationPayload.ExecutionTerminal value) {
            builder.status(source.status())
                    .terminalStatus(value.terminalStatus())
                    .stopReason(value.stopReason());
        } else {
            builder.unobservedTechnicalStatus();
        }
        return builder.build();
    }

    private static void addPayloadDimensions(
            MetricDimensions.Builder builder,
            RetrievalObservationPayload payload
    ) {
        if (payload instanceof RetrievalObservationPayload.QueryAnalysisCompleted value) {
            builder.componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.QueryPlanningCompleted value) {
            builder.componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.RetrievalPlanCompleted value) {
            builder.strategy(value.strategy());
        } else if (payload instanceof RetrievalObservationPayload.RetrievalBranchCompleted value) {
            builder.strategy(value.strategy())
                    .channel(value.channel())
                    .componentModel(value.componentVersion())
                    .dataIndexVersion(value.dataIndexVersion());
        } else if (payload instanceof RetrievalObservationPayload.FusionCompleted value) {
            builder.strategy(value.strategy()).componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.RerankCompleted value) {
            builder.strategy(value.strategy()).componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.CoverageCheckCompleted value) {
            builder.componentModel(value.componentVersion())
                    .coverageStatus(value.terminalStatus());
        } else if (payload instanceof RetrievalObservationPayload.ChainNodeEvaluated value) {
            builder.chainNode(value.node());
        } else if (payload instanceof RetrievalObservationPayload.ChainNodeCompleted value) {
            builder.chainNode(value.node()).strategy(value.strategy());
        }
    }
}
