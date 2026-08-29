package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 将逐层原始事件和 execution 投影为不依赖 Gold 标注的运行指标事实。
 *
 * <p>逐事件事实是追加型，执行完整性、请求分母和最终 Coverage 结论是可替换快照。
 * 该投影器只进行确定性的计数、耗时和技术终态计算，不推断检索相关性或证据质量。
 * 离线 Gold 指标必须由独立计算器在拿到 Dataset/Case 标注后生成
 * {@link MetricFact.OfflineGold}。</p>
 */
public final class RuntimeMetricFactProjector {
    public static final int METRIC_DEFINITION_VERSION = 3;

    /**
     * 投影一条层事件。事件存储端应使用 sourceEventId、metricKey、版本和维度保证幂等。
     */
    public List<MetricFact.Runtime> project(RetrievalObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        MetricDimensions dimensions = dimensions(observation);
        String stage = observation.stage().name().toLowerCase(Locale.ROOT);
        List<MetricFact.Runtime> facts = new ArrayList<>();
        facts.add(fact(
                observation,
                "retrieval.stage." + stage + ".event.count",
                MetricFact.Aggregation.COUNT,
                1.0D,
                dimensions
        ));
        facts.add(fact(
                observation,
                "retrieval.stage." + stage + ".duration.ms",
                MetricFact.Aggregation.DISTRIBUTION,
                observation.duration().toMillis(),
                dimensions
        ));
        facts.add(fact(
                observation,
                "retrieval.stage." + stage + ".input.count",
                MetricFact.Aggregation.DISTRIBUTION,
                observation.payload().inputCount(),
                dimensions
        ));
        facts.add(fact(
                observation,
                "retrieval.stage." + stage + ".output.count",
                MetricFact.Aggregation.DISTRIBUTION,
                observation.payload().outputCount(),
                dimensions
        ));
        facts.addAll(payloadFacts(observation, dimensions));
        if (observation.terminal()) {
            facts.addAll(terminalFacts(observation, dimensions));
        }
        return List.copyOf(facts);
    }

    /**
     * 投影一个 execution 唯一的可替换指标快照。
     *
     * <p>请求分母和观测完整性从收到第一条事件起就存在，因此缺终态执行不会消失。
     * Coverage 通过率只在业务终态明确为 SUFFICIENT 或 INSUFFICIENT 时生成，优化过程的
     * CONTINUE 和未执行、失败结论不会进入分母。</p>
     */
    public List<MetricFact.Runtime> projectExecution(
            RetrievalExecutionObservation execution
    ) {
        Objects.requireNonNull(execution, "execution must not be null");
        RetrievalObservation source = execution.terminalEvent()
                .orElseGet(() -> execution.events().getLast());
        MetricDimensions dimensions = executionDimensions(execution, source);
        List<MetricFact.Runtime> facts = new ArrayList<>();
        facts.add(fact(
                source,
                "retrieval.request.count",
                MetricFact.Aggregation.COUNT,
                1.0D,
                dimensions
        ));
        facts.add(fact(
                source,
                "retrieval.observation.complete",
                MetricFact.Aggregation.DISTRIBUTION,
                execution.completeness() == ObservationCompleteness.COMPLETE
                        ? 1.0D : 0.0D,
                dimensions
        ));
        execution.terminalEvent().ifPresent(terminal -> {
            var payload = (RetrievalObservationPayload.ExecutionTerminal) terminal.payload();
            if (payload.terminalStatus() == RetrievalTerminalStatus.SUFFICIENT
                    || payload.terminalStatus() == RetrievalTerminalStatus.INSUFFICIENT) {
                facts.add(fact(
                        terminal,
                        "retrieval.coverage.sufficient",
                        MetricFact.Aggregation.DISTRIBUTION,
                        payload.terminalStatus() == RetrievalTerminalStatus.SUFFICIENT
                                ? 1.0D : 0.0D,
                        dimensions
                ));
            }
        });
        return List.copyOf(facts);
    }

    private static List<MetricFact.Runtime> terminalFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions
    ) {
        var payload = (RetrievalObservationPayload.ExecutionTerminal) observation.payload();
        return List.of(
                fact(
                        observation,
                        "retrieval.request.technical_success",
                        MetricFact.Aggregation.DISTRIBUTION,
                        successful(payload.terminalStatus()) ? 1.0D : 0.0D,
                        dimensions
                ),
                fact(
                        observation,
                        "retrieval.request.degraded",
                        MetricFact.Aggregation.DISTRIBUTION,
                        payload.degraded() ? 1.0D : 0.0D,
                        dimensions
                ),
                fact(
                        observation,
                        "retrieval.request.result.count",
                        MetricFact.Aggregation.DISTRIBUTION,
                        payload.resultCount(),
                        dimensions
                ),
                fact(
                        observation,
                        "retrieval.request.attempt.count",
                        MetricFact.Aggregation.DISTRIBUTION,
                        payload.retrievalAttemptCount(),
                        dimensions
                )
        );
    }

    /**
     * 只把强类型载荷已经明确给出的事实转成指标；缺失的模型评分或 Token 计数不补零。
     */
    private static List<MetricFact.Runtime> payloadFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions
    ) {
        RetrievalObservationPayload payload = observation.payload();
        List<MetricFact.Runtime> facts = new ArrayList<>();
        if (payload instanceof RetrievalObservationPayload.RetrievalBranchCompleted branch) {
            if (observation.status() == RetrievalObservationStatus.SUCCEEDED) {
                facts.add(fact(
                        observation,
                        "retrieval.branch.empty",
                        MetricFact.Aggregation.DISTRIBUTION,
                        branch.candidates().isEmpty() ? 1.0D : 0.0D,
                        dimensions
                ));
            }
            facts.add(fact(
                    observation,
                    "retrieval.branch.success",
                    MetricFact.Aggregation.DISTRIBUTION,
                    successful(observation.status()) ? 1.0D : 0.0D,
                    dimensions
            ));
        } else if (payload instanceof RetrievalObservationPayload.FusionCompleted fusion) {
            facts.add(fact(
                    observation,
                    "retrieval.fusion.input_candidate.count",
                    MetricFact.Aggregation.DISTRIBUTION,
                    fusion.inputCandidateCount(),
                    dimensions
            ));
            facts.add(fact(
                    observation,
                    "retrieval.fusion.unique_candidate.count",
                    MetricFact.Aggregation.DISTRIBUTION,
                    fusion.uniqueCandidateCount(),
                    dimensions
            ));
            if (fusion.inputCandidateCount() > 0) {
                int duplicateCount = fusion.inputCandidateCount()
                        - fusion.uniqueCandidateCount();
                facts.add(fact(
                        observation,
                        "retrieval.fusion.duplicate.rate",
                        MetricFact.Aggregation.RATIO_NUMERATOR,
                        duplicateCount,
                        dimensions
                ));
                facts.add(fact(
                        observation,
                        "retrieval.fusion.duplicate.rate",
                        MetricFact.Aggregation.RATIO_DENOMINATOR,
                        fusion.inputCandidateCount(),
                        dimensions
                ));
            }
        } else if (payload instanceof RetrievalObservationPayload.RerankCompleted rerank) {
            facts.add(fact(
                    observation,
                    "retrieval.rerank.executed",
                    MetricFact.Aggregation.DISTRIBUTION,
                    rerank.executed() ? 1.0D : 0.0D,
                    dimensions
            ));
            facts.add(fact(
                    observation,
                    "retrieval.rerank.fallback",
                    MetricFact.Aggregation.DISTRIBUTION,
                    rerank.fallback() ? 1.0D : 0.0D,
                    dimensions
            ));
            addUsageFacts(observation, dimensions, "retrieval.rerank", rerank.usage(), facts);
        } else if (payload instanceof RetrievalObservationPayload.CoverageCheckCompleted coverage) {
            addCoverageFacts(observation, dimensions, coverage, facts);
        } else if (payload instanceof RetrievalObservationPayload.ChainNodeCompleted node) {
            facts.add(fact(
                    observation,
                    "retrieval.chain.node.executed.count",
                    MetricFact.Aggregation.COUNT,
                    1.0D,
                    dimensions
            ));
            if (node.coverageBefore().present() && node.coverageAfter().present()) {
                facts.add(fact(
                        observation,
                        "retrieval.chain.node.coverage.delta",
                        MetricFact.Aggregation.DISTRIBUTION,
                        node.coverageAfter().value() - node.coverageBefore().value(),
                        dimensions
                ));
            }
            addUsageFacts(
                    observation,
                    dimensions,
                    "retrieval.chain.node",
                    node.usage(),
                    facts
            );
        } else if (payload instanceof RetrievalObservationPayload.SpaceChanged) {
            facts.add(fact(
                    observation,
                    "retrieval.space.changed.count",
                    MetricFact.Aggregation.COUNT,
                    1.0D,
                    dimensions
            ));
        } else if (payload instanceof RetrievalObservationPayload.QueryPlanningCompleted planning) {
            addUsageFacts(
                    observation,
                    dimensions,
                    "retrieval.query_planning",
                    planning.usage(),
                    facts
            );
        }
        return facts;
    }

    private static void addCoverageFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            RetrievalObservationPayload.CoverageCheckCompleted coverage,
            List<MetricFact.Runtime> facts
    ) {
        if (coverage.coverageScore().present()) {
            facts.add(fact(
                    observation,
                    "retrieval.coverage.score",
                    MetricFact.Aggregation.DISTRIBUTION,
                    coverage.coverageScore().value(),
                    dimensions
            ));
        }
        facts.add(fact(
                observation,
                "retrieval.coverage.retained_candidate.count",
                MetricFact.Aggregation.DISTRIBUTION,
                coverage.retainedCandidateCount(),
                dimensions
        ));
        addUsageFacts(observation, dimensions, "retrieval.coverage", coverage.usage(), facts);
    }

    /** 模型调用数始终可观测；Provider 未给 Token 时不使用零占位生成指标。 */
    private static void addUsageFacts(
            RetrievalObservation observation,
            MetricDimensions dimensions,
            String prefix,
            RetrievalObservationPayload.UsageCount usage,
            List<MetricFact.Runtime> facts
    ) {
        facts.add(fact(
                observation,
                prefix + ".model_request.count",
                MetricFact.Aggregation.SUM,
                usage.requestCount(),
                dimensions
        ));
        if (!usage.tokenCountsAvailable()) {
            return;
        }
        facts.add(fact(
                observation,
                prefix + ".model_input_token.count",
                MetricFact.Aggregation.SUM,
                usage.inputTokenCount(),
                dimensions
        ));
        facts.add(fact(
                observation,
                prefix + ".model_output_token.count",
                MetricFact.Aggregation.SUM,
                usage.outputTokenCount(),
                dimensions
        ));
    }

    private static boolean successful(RetrievalObservationStatus status) {
        return status == RetrievalObservationStatus.SUCCEEDED
                || status == RetrievalObservationStatus.DEGRADED;
    }

    /** 业务终态明确区分“覆盖不足但正常返回”和 Coverage 技术失败。 */
    private static boolean successful(RetrievalTerminalStatus terminalStatus) {
        return switch (terminalStatus) {
            case SUFFICIENT, INSUFFICIENT, NOT_EVALUATED,
                    EVIDENCE_REQUIREMENTS_MISSING -> true;
            case CHECK_FAILED, TECHNICAL_FAILED -> false;
        };
    }

    private static MetricFact.Runtime fact(
            RetrievalObservation observation,
            String metricKey,
            MetricFact.Aggregation aggregation,
            double value,
            MetricDimensions dimensions
    ) {
        return new MetricFact.Runtime(
                observation.eventId(),
                observation.executionId(),
                observation.tenantId(),
                metricKey,
                METRIC_DEFINITION_VERSION,
                aggregation,
                value,
                dimensions,
                observation.completedAt()
        );
    }

    private static MetricDimensions dimensions(RetrievalObservation observation) {
        MetricDimensions.Builder builder = MetricDimensions.builder()
                .config(observation.configFingerprint())
                .attempt(observation.attemptIndex())
                .status(observation.status())
                .purpose(observation.purpose())
                .timeSlice(
                        observation.completedAt(),
                        MetricDimensions.TimeSliceGranularity.HOUR
                );
        if (observation.spaceIds().size() == 1) {
            builder.space(observation.spaceIds().iterator().next());
        }
        if (observation.payload() instanceof RetrievalObservationPayload.ExecutionTerminal value) {
            builder.terminalStatus(value.terminalStatus()).stopReason(value.stopReason());
        }
        addPayloadDimensions(builder, observation.payload());
        return builder.build();
    }

    /** 执行级事实使用首个已知事件归属时间窗口，并使用当前最终状态和配置。 */
    private static MetricDimensions executionDimensions(
            RetrievalExecutionObservation execution,
            RetrievalObservation source
    ) {
        Instant cohortAt = execution.startedEvent()
                .map(RetrievalObservation::startedAt)
                .orElseGet(() -> execution.events().getFirst().startedAt());
        MetricDimensions.Builder builder = MetricDimensions.builder()
                .config(source.configFingerprint())
                .status(source.status())
                .purpose(execution.purpose())
                .timeSlice(cohortAt, MetricDimensions.TimeSliceGranularity.HOUR);
        if (source.spaceIds().size() == 1) {
            builder.space(source.spaceIds().iterator().next());
        }
        if (source.payload() instanceof RetrievalObservationPayload.ExecutionTerminal value) {
            builder.terminalStatus(value.terminalStatus()).stopReason(value.stopReason());
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
                    .componentModel(value.componentVersion())
                    .dataIndexVersion(value.dataIndexVersion());
        } else if (payload instanceof RetrievalObservationPayload.FusionCompleted value) {
            builder.strategy(value.strategy()).componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.RerankCompleted value) {
            builder.strategy(value.strategy()).componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.CoverageCheckCompleted value) {
            builder.componentModel(value.componentVersion());
        } else if (payload instanceof RetrievalObservationPayload.ChainNodeCompleted value) {
            builder.strategy(value.strategy());
        }
    }
}
