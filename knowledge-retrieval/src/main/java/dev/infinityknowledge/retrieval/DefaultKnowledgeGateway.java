package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.EvidenceRequirement;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.retrieval.configuration.EffectiveRetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.retrieval.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.ResolvedRetrievalComponents;
import dev.infinityknowledge.retrieval.fusion.FusedCandidate;
import dev.infinityknowledge.retrieval.fusion.RankedCandidateList;
import dev.infinityknowledge.retrieval.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.retrieval.query.PlannedQuery;
import dev.infinityknowledge.retrieval.query.QueryOptimizationPlan;
import dev.infinityknowledge.retrieval.query.ResolvedConstraints;
import dev.infinityknowledge.retrieval.support.Hashing;
import dev.infinityknowledge.spi.KnowledgeGateway;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.indexing.ActiveIndexGeneration;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.QueryAnalyzer;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalTextFingerprinter;
import dev.infinityknowledge.spi.trace.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 编排授权、查询分析与规划、多路召回、活动修订校验、融合、精排和安全 Trace。
 */
public final class DefaultKnowledgeGateway implements KnowledgeGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DefaultKnowledgeGateway.class
    );
    private final AccessPolicy accessPolicy;
    private final ActiveRevisionGuard activeRevisionGuard;
    private final QueryAnalyzer queryAnalyzer;
    private final SpaceRoutingStage spaceRoutingStage;
    private final SpaceConfigurationStage spaceConfigurationStage;
    private final RetrievalPlanner retrievalPlanner;
    private final RetrievalComponentRegistry componentRegistry;
    private final ActiveIndexGenerationCatalog activeIndexGenerationCatalog;
    private final OptimizationChainRunner chainRunner;
    private final ReciprocalRankFusion fusion;
    private final DefaultEvidenceBuilder evidenceBuilder;
    private final TraceSink traceSink;
    private final RetrievalObservationPublisher observationPublisher;
    private final RetrievalTextFingerprinter textFingerprinter;
    private final Executor executor;
    private final Clock clock;
    private final double sufficientThreshold;
    private final Duration requestTimeout;
    private final Duration queryPlannerTimeout;
    private final Duration channelTimeout;
    private final Duration rerankerTimeout;
    private final Duration coverageTimeout;

    /**
     * 创建读取 Space 物化配置、执行模型 Space 排序的生产检索门面。
     *
     * <p>该构造器是唯一权威装配入口，所有运行环境都必须显式提供 Space 路由、
     * 物化配置和观测端口，防止测试默认值静默进入生产。</p>
     */
    public DefaultKnowledgeGateway(
            AccessPolicy accessPolicy,
            ActiveRevisionGuard activeRevisionGuard,
            QueryAnalyzer queryAnalyzer,
            RetrievalComponentRegistry componentRegistry,
            ActiveIndexGenerationCatalog activeIndexGenerationCatalog,
            ReciprocalRankFusion fusion,
            DefaultEvidenceBuilder evidenceBuilder,
            TraceSink traceSink,
            RetrievalSpaceCatalog spaceCatalog,
            SpaceRouter spaceRouter,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationResolver configurationResolver,
            RetrievalConfigurationHardLimits hardLimits,
            RetrievalObservationPublisher observationPublisher,
            RetrievalTextFingerprinter textFingerprinter,
            Executor executor,
            Clock clock,
            double sufficientThreshold,
            Duration requestTimeout,
            Duration spaceRouterTimeout,
            Duration queryPlannerTimeout,
            Duration channelTimeout,
            Duration rerankerTimeout,
            Duration coverageTimeout
    ) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
        this.queryAnalyzer = Objects.requireNonNull(queryAnalyzer, "queryAnalyzer must not be null");
        this.componentRegistry = Objects.requireNonNull(
                componentRegistry,
                "componentRegistry must not be null"
        );
        this.activeIndexGenerationCatalog = Objects.requireNonNull(
                activeIndexGenerationCatalog,
                "activeIndexGenerationCatalog must not be null"
        );
        this.fusion = Objects.requireNonNull(fusion, "fusion must not be null");
        this.evidenceBuilder = Objects.requireNonNull(
                evidenceBuilder,
                "evidenceBuilder must not be null"
        );
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink must not be null");
        this.observationPublisher = Objects.requireNonNull(
                observationPublisher,
                "observationPublisher must not be null"
        );
        this.textFingerprinter = Objects.requireNonNull(
                textFingerprinter,
                "textFingerprinter must not be null"
        );
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (!Double.isFinite(sufficientThreshold)
                || sufficientThreshold < 0.0D
                || sufficientThreshold > 1.0D) {
            throw new IllegalArgumentException(
                    "sufficientThreshold must be finite and between 0 and 1"
            );
        }
        this.sufficientThreshold = sufficientThreshold;
        this.requestTimeout = requirePositiveTimeout(requestTimeout, "requestTimeout");
        Duration validatedSpaceRouterTimeout = requirePositiveTimeout(
                spaceRouterTimeout,
                "spaceRouterTimeout"
        );
        this.coverageTimeout = requirePositiveTimeout(
                coverageTimeout,
                "coverageTimeout"
        );
        this.queryPlannerTimeout = requirePositiveTimeout(
                queryPlannerTimeout,
                "queryPlannerTimeout"
        );
        this.channelTimeout = requirePositiveTimeout(channelTimeout, "channelTimeout");
        this.rerankerTimeout = requirePositiveTimeout(rerankerTimeout, "rerankerTimeout");
        if (this.channelTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "channelTimeout must not be greater than requestTimeout"
            );
        }
        if (validatedSpaceRouterTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "spaceRouterTimeout must not be greater than requestTimeout"
            );
        }
        if (this.coverageTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "coverageTimeout must not be greater than requestTimeout"
            );
        }
        if (this.queryPlannerTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "queryPlannerTimeout must not be greater than requestTimeout"
            );
        }
        if (this.rerankerTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "rerankerTimeout must not be greater than requestTimeout"
            );
        }
        this.retrievalPlanner = new RetrievalPlanner(RetrieverAssignmentMode.RULE_ONLY);
        this.spaceRoutingStage = new SpaceRoutingStage(
                spaceCatalog,
                spaceRouter,
                executor,
                clock,
                validatedSpaceRouterTimeout
        );
        this.spaceConfigurationStage = new SpaceConfigurationStage(
                configurationStore,
                Objects.requireNonNull(
                        configurationResolver,
                        "configurationResolver must not be null"
                ),
                hardLimits
        );
        this.chainRunner = new OptimizationChainRunner();
    }

    /**
     * 执行完整检索流程；单通道失败会降级并产生警告，跨租户候选会立即拒绝。
     *
     * @param query 已绑定认证主体的知识查询
     * @return 可追溯证据包
     */
    @Override
    public EvidenceBundle retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(requestTimeout);
        long requestDeadlineNanos = System.nanoTime() + requestTimeout.toNanos();
        UUID traceId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        List<RetrievalStepTrace> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        RetrievalObservationEmitter observations = new RetrievalObservationEmitter(
                observationPublisher,
                textFingerprinter,
                clock,
                query,
                executionId,
                warnings
        );
        int consumedRetrievalAttempts = 0;
        observations.started(startedAt);

        try {
        observations.beginStage("ACCESS_POLICY", 1, startedAt);
        AccessScope scope = measure(
                "ACCESS_POLICY",
                1,
                steps,
                () -> accessPolicy.resolve(query.principal(), query.spaceIds())
        );
        validateAccessScope(query, scope);
        Instant routingStartedAt = clock.instant();
        observations.beginStage("SPACE_ROUTING", scope.spaceIds().size(), routingStartedAt);
        SpaceRoutingStage.Result routedSpaces = spaceRoutingStage.route(
                query,
                scope,
                steps,
                warnings,
                requestDeadlineNanos
        );
        List<KnowledgeSpaceId> orderedSpaces = routedSpaces.spaceIds();
        observations.routing(
                Set.copyOf(orderedSpaces),
                new RetrievalObservationPayload.SpaceRoutingCompleted(
                        scope.spaceIds().size(),
                        java.util.stream.IntStream.range(0, orderedSpaces.size())
                                .mapToObj(index -> new RetrievalObservationPayload.RankedSpace(
                                        orderedSpaces.get(index),
                                        index + 1
                                ))
                                .toList()
                ),
                routedSpaces.degraded()
                        ? RetrievalObservationStatus.DEGRADED
                        : RetrievalObservationStatus.SUCCEEDED,
                routedSpaces.degraded() ? "SPACE_ROUTER_FALLBACK" : "NONE",
                routingStartedAt
        );
        int currentSpaceIndex = 0;
        KnowledgeSpaceId currentSpace = orderedSpaces.getFirst();
        observations.enterVisit(currentSpaceIndex, currentSpace);
        Instant configurationStartedAt = clock.instant();
        observations.beginStage("CONFIGURATION_RESOLVED", 1, configurationStartedAt);
        EffectiveRetrievalConfiguration effectiveConfiguration = resolveConfiguration(
                query,
                currentSpace,
                steps
        );
        ResolvedRetrievalComponents resolvedComponents = componentRegistry.resolve(
                effectiveConfiguration.configuration()
        );
        ActiveIndexGeneration activeIndexGeneration = requireActiveIndexGeneration(
                query,
                currentSpace
        );
        observations.resolvedConfiguration(effectiveConfiguration.fingerprint());
        emitConfigurationResolved(
                observations,
                effectiveConfiguration,
                resolvedComponents,
                activeIndexGeneration,
                configurationStartedAt
        );
        int maximumRetrievalAttempts = effectiveConfiguration.configuration()
                .maximumRetrievalAttempts();
        List<KnowledgeSpaceId> visitedSpaces = new ArrayList<>();
        List<String> configurationFingerprints = new ArrayList<>();
        visitedSpaces.add(currentSpace);
        configurationFingerprints.add(effectiveConfiguration.fingerprint());
        Map<KnowledgeSpaceId, EnumSet<RetrievalConfiguration.ChainNode>> consumedNodes =
                new HashMap<>();
        consumedNodes.put(
                currentSpace,
                EnumSet.noneOf(RetrievalConfiguration.ChainNode.class)
        );

        QueryOptimizationPlan pendingFeedbackPlan = null;
        ResolvedConstraints currentResolvedConstraints = ResolvedConstraints.initial(query);
        List<FusedCandidate> evidenceMemory = List.of();
        List<FusedCandidate> finalCandidates = List.of();
        double coverage = 0.0D;
        List<String> missingGaps = List.of();
        RetrievalTerminalStatus terminalStatus;
        RetrievalStopReason stopReason;
        PendingChainObservation pendingChainObservation = null;

        execution:
        while (true) {
            observations.attempt(consumedRetrievalAttempts);
            KnowledgeQuery currentQuery = forSpace(query, currentSpace);
            RetrievalConfiguration configuration = effectiveConfiguration.configuration();
            List<FusedCandidate> currentCandidates = executeAttempt(
                    currentQuery,
                    scope.forSpace(currentSpace),
                    configuration,
                    resolvedComponents,
                    activeIndexGeneration,
                    pendingFeedbackPlan,
                    currentResolvedConstraints,
                    deadline,
                    steps,
                    warnings,
                    requestDeadlineNanos,
                    observations
            );
            pendingFeedbackPlan = null;
            consumedRetrievalAttempts++;
            Instant coverageStartedAt = clock.instant();
            observations.beginStage(
                    "COVERAGE_CHECK",
                    uniqueCandidateCount(evidenceMemory, currentCandidates),
                    coverageStartedAt
            );

            if (!configuration.coverage().enabled()) {
                finalCandidates = mergeEvidenceMemory(
                        evidenceMemory,
                        currentCandidates,
                        query.topK()
                );
                emitCoverageSkipped(
                        observations,
                        configuration,
                        resolvedComponents.coverageJudge().version(),
                        finalCandidates.size(),
                        0,
                        RetrievalObservationPayload.CoverageTerminalStatus.NOT_EVALUATED,
                        "COVERAGE_DISABLED"
                );
                completePendingChain(
                        observations,
                        pendingChainObservation,
                        finalCandidates.size(),
                        RetrievalObservationPayload.OptionalScore.absent(),
                        false,
                        "COVERAGE_DISABLED"
                );
                pendingChainObservation = null;
                terminalStatus = RetrievalTerminalStatus.NOT_EVALUATED;
                stopReason = RetrievalStopReason.COVERAGE_DISABLED;
                break;
            }

            List<EvidenceRequirement> requirements = resolveEvidenceRequirements(query);
            if (requirements.isEmpty()) {
                finalCandidates = mergeEvidenceMemory(
                        evidenceMemory,
                        currentCandidates,
                        query.topK()
                );
                emitCoverageSkipped(
                        observations,
                        configuration,
                        resolvedComponents.coverageJudge().version(),
                        finalCandidates.size(),
                        0,
                        RetrievalObservationPayload.CoverageTerminalStatus
                                .EVIDENCE_REQUIREMENTS_MISSING,
                        "EVIDENCE_REQUIREMENTS_MISSING"
                );
                completePendingChain(
                        observations,
                        pendingChainObservation,
                        finalCandidates.size(),
                        RetrievalObservationPayload.OptionalScore.absent(),
                        false,
                        "EVIDENCE_REQUIREMENTS_MISSING"
                );
                pendingChainObservation = null;
                terminalStatus = RetrievalTerminalStatus.EVIDENCE_REQUIREMENTS_MISSING;
                stopReason = RetrievalStopReason.EVIDENCE_REQUIREMENTS_MISSING;
                break;
            }
            KnowledgeQuery coverageQuery = withEvidenceRequirements(
                    currentQuery,
                    requirements
            );
            CoverageStage.Result coverageResult = new CoverageStage(
                    resolvedComponents.coverageJudge().implementation(),
                    executor,
                    clock,
                    coverageTimeout
            ).evaluate(
                    coverageQuery,
                    evidenceMemory,
                    currentCandidates,
                    configuration.coverage().memoryLimit(),
                    steps,
                    warnings,
                    requestDeadlineNanos
            );
            RetrievalObservationPayload.CoverageTerminalStatus coverageTerminalStatus =
                    coverageResult.checkFailed()
                            ? RetrievalObservationPayload.CoverageTerminalStatus.CHECK_FAILED
                            : coverageResult.coverage()
                                    >= configuration.coverage().sufficiencyThreshold()
                                    ? RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT
                                    : consumedRetrievalAttempts >= maximumRetrievalAttempts
                                            ? RetrievalObservationPayload.CoverageTerminalStatus
                                                    .INSUFFICIENT
                                            : RetrievalObservationPayload.CoverageTerminalStatus
                                                    .CONTINUE;
            emitCoverageCompleted(
                    observations,
                    configuration,
                    resolvedComponents.coverageJudge().version(),
                    requirements.size(),
                    uniqueCandidateCount(evidenceMemory, currentCandidates),
                    coverageResult,
                    coverageTerminalStatus,
                    coverageStartedAt
            );
            completePendingChain(
                    observations,
                    pendingChainObservation,
                    coverageResult.retainedCandidates().size(),
                    coverageResult.checkFailed()
                            ? RetrievalObservationPayload.OptionalScore.absent()
                            : RetrievalObservationPayload.OptionalScore.of(
                                    coverageResult.coverage()
                            ),
                    coverageResult.checkFailed(),
                    coverageResult.checkFailed()
                            ? "COVERAGE_CHECK_FAILED" : "COMPLETED"
            );
            pendingChainObservation = null;
            if (coverageResult.checkFailed()) {
                finalCandidates = coverageResult.retainedCandidates().isEmpty()
                        ? currentCandidates
                        : coverageResult.retainedCandidates();
                terminalStatus = RetrievalTerminalStatus.CHECK_FAILED;
                stopReason = RetrievalStopReason.COVERAGE_CHECK_FAILED;
                break;
            }
            coverage = coverageResult.coverage();
            missingGaps = coverageResult.missingGaps();
            evidenceMemory = coverageResult.retainedCandidates();
            finalCandidates = evidenceMemory;
            if (coverage >= configuration.coverage().sufficiencyThreshold()) {
                terminalStatus = RetrievalTerminalStatus.SUFFICIENT;
                stopReason = RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED;
                break;
            }
            if (consumedRetrievalAttempts >= maximumRetrievalAttempts) {
                terminalStatus = RetrievalTerminalStatus.INSUFFICIENT;
                stopReason = RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED;
                break;
            }

            while (true) {
                EnumSet<RetrievalConfiguration.ChainNode> currentConsumed =
                        consumedNodes.get(currentSpace);
                int remainingAttemptBudget = Math.max(
                        0,
                        maximumRetrievalAttempts - consumedRetrievalAttempts
                );
                Instant chainEvaluationStartedAt = clock.instant();
                observations.beginStage(
                        "CHAIN_NODE_EVALUATION",
                        1,
                        chainEvaluationStartedAt
                );
                var nextNode = chainRunner.next(
                        new OptimizationChainRunner.Context(
                                configuration,
                                currentSpace,
                                currentSpaceIndex,
                                orderedSpaces,
                                visitedSpaces.size(),
                                coverage,
                                missingGaps,
                                evidenceMemory,
                                currentResolvedConstraints,
                                currentConsumed
                        ),
                        steps,
                        evaluation -> observations.emit(
                                new RetrievalObservationPayload.ChainNodeEvaluated(
                                        evaluation.node().name(),
                                        evaluation.applicable(),
                                        evaluation.reasonCode(),
                                        remainingAttemptBudget
                                ),
                                evaluation.applicable()
                                        ? RetrievalObservationStatus.SUCCEEDED
                                        : RetrievalObservationStatus.SKIPPED,
                                evaluation.applicable()
                                        ? "NONE" : evaluation.reasonCode(),
                                clock.instant()
                        )
                );
                if (nextNode.isEmpty()) {
                    terminalStatus = RetrievalTerminalStatus.INSUFFICIENT;
                    stopReason = chainExhausted(configuration, currentConsumed)
                            ? RetrievalStopReason.OPTIMIZATION_CHAIN_EXHAUSTED
                            : RetrievalStopReason.NO_APPLICABLE_OPTIMIZATION_NODE;
                    break execution;
                }
                RetrievalConfiguration.ChainNode node = nextNode.get();
                currentConsumed.add(node);
                if (node == RetrievalConfiguration.ChainNode.NEXT_SPACE) {
                    pendingChainObservation = new PendingChainObservation(
                            node,
                            node.name(),
                            evidenceMemory.size(),
                            RetrievalObservationPayload.OptionalScore.of(coverage),
                            RetrievalObservationPayload.UsageCount.none(),
                            clock.instant()
                    );
                    KnowledgeSpaceId previousSpace = currentSpace;
                    currentSpaceIndex++;
                    currentSpace = orderedSpaces.get(currentSpaceIndex);
                    observations.beginStage("SPACE_CHANGE", 1, clock.instant());
                    observations.spaceChanged(
                            previousSpace,
                            currentSpace,
                            new RetrievalObservationPayload.SpaceChanged(
                                    previousSpace,
                                    currentSpace,
                                    "NEXT_SPACE"
                            ),
                            clock.instant()
                    );
                    observations.enterVisit(currentSpaceIndex, currentSpace);
                    configurationStartedAt = clock.instant();
                    observations.beginStage(
                            "CONFIGURATION_RESOLVED",
                            1,
                            configurationStartedAt
                    );
                    effectiveConfiguration = resolveConfiguration(
                            query,
                            currentSpace,
                            steps
                    );
                    resolvedComponents = componentRegistry.resolve(
                            effectiveConfiguration.configuration()
                    );
                    activeIndexGeneration = requireActiveIndexGeneration(
                            query,
                            currentSpace
                    );
                    observations.resolvedConfiguration(effectiveConfiguration.fingerprint());
                    emitConfigurationResolved(
                            observations,
                            effectiveConfiguration,
                            resolvedComponents,
                            activeIndexGeneration,
                            configurationStartedAt
                    );
                    visitedSpaces.add(currentSpace);
                    configurationFingerprints.add(effectiveConfiguration.fingerprint());
                    consumedNodes.put(
                            currentSpace,
                            EnumSet.noneOf(RetrievalConfiguration.ChainNode.class)
                    );
                    currentResolvedConstraints = ResolvedConstraints.initial(query);
                    break;
                }
                if (node == RetrievalConfiguration.ChainNode.RELAX_CONSTRAINTS
                        || node == RetrievalConfiguration.ChainNode.NARROW_CONSTRAINTS) {
                    observations.beginStage("CONSTRAINT_CHANGE", 1, clock.instant());
                    var changedConstraints = node
                            == RetrievalConfiguration.ChainNode.RELAX_CONSTRAINTS
                            ? currentResolvedConstraints.relaxNext()
                            : currentResolvedConstraints.narrowNext();
                    if (changedConstraints.isEmpty()) {
                        continue;
                    }
                    currentResolvedConstraints = changedConstraints.get();
                    pendingChainObservation = new PendingChainObservation(
                            node,
                            node.name(),
                            evidenceMemory.size(),
                            RetrievalObservationPayload.OptionalScore.of(coverage),
                            RetrievalObservationPayload.UsageCount.none(),
                            clock.instant()
                    );
                    pendingFeedbackPlan = QueryOptimizationPlan.constraintChange(
                            currentResolvedConstraints,
                            currentQuery.text()
                    );
                    break;
                }
                var strategy = variantKind(node);
                if (strategy.isEmpty()) {
                    continue;
                }
                Instant feedbackStartedAt = clock.instant();
                observations.beginStage(
                        "FEEDBACK_QUERY_PLANNING",
                        1,
                        feedbackStartedAt
                );
                var planned = new FeedbackPlanningStage(
                        resolvedComponents.feedbackQueryPlanner().implementation(),
                        executor,
                        clock,
                        queryPlannerTimeout
                ).plan(
                        currentQuery,
                        strategy.get(),
                        missingGaps,
                        evidenceMemory,
                        steps,
                        warnings,
                        requestDeadlineNanos
                );
                if (planned.isPresent()) {
                    pendingChainObservation = new PendingChainObservation(
                            node,
                            node.name(),
                            evidenceMemory.size(),
                            RetrievalObservationPayload.OptionalScore.of(coverage),
                            RetrievalObservationPayload.UsageCount.unmeasured(1),
                            feedbackStartedAt
                    );
                    pendingFeedbackPlan = QueryOptimizationPlan.feedback(
                            currentResolvedConstraints,
                            planned.get()
                    );
                    break;
                }
                completePendingChain(
                        observations,
                        new PendingChainObservation(
                                node,
                                node.name(),
                                evidenceMemory.size(),
                                RetrievalObservationPayload.OptionalScore.of(coverage),
                                RetrievalObservationPayload.UsageCount.unmeasured(1),
                                feedbackStartedAt
                        ),
                        evidenceMemory.size(),
                        RetrievalObservationPayload.OptionalScore.absent(),
                        true,
                        "QUERY_GENERATION_UNAVAILABLE"
                );
            }
        }

        List<FusedCandidate> candidatesForEvidence = finalCandidates;
        Instant evidenceBuildStartedAt = clock.instant();
        observations.beginStage(
                "EVIDENCE_BUILD",
                candidatesForEvidence.size(),
                evidenceBuildStartedAt
        );
        List<Evidence> evidence = measure(
                "EVIDENCE_BUILD",
                candidatesForEvidence.size(),
                steps,
                () -> evidenceBuilder.build(candidatesForEvidence)
        );
        observations.emit(
                new RetrievalObservationPayload.EvidenceBuildCompleted(
                        candidatesForEvidence.size(),
                        java.util.stream.IntStream.range(0, candidatesForEvidence.size())
                                .mapToObj(index ->
                                        new RetrievalObservationPayload.RankedCandidate(
                                                candidateIdentity(
                                                        candidatesForEvidence.get(index)
                                                                .representative()
                                                ),
                                                index + 1,
                                                candidatesForEvidence.get(index).relevance()
                                        )
                                ).toList()
                ),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                evidenceBuildStartedAt
        );
        observations.beginStage("EXECUTION_FINALIZATION", evidence.size(), clock.instant());
        Instant completedAt = clock.instant();
        RetrievalTrace trace = new RetrievalTrace(
                traceId,
                query.requestId(),
                query.principal().tenantId(),
                query.principal().principalId(),
                Hashing.sha256(query.text()),
                steps,
                nonNegativeDuration(startedAt, completedAt),
                evidence.size(),
                startedAt
        );
        try {
            traceSink.append(trace);
        } catch (RuntimeException traceFailure) {
            warnings.add("TRACE_PERSISTENCE_FAILED");
            LOGGER.error(
                    "Trace persistence failed: traceId={}, requestId={}, tenantId={}, "
                            + "failureType={}",
                    traceId,
                    query.requestId(),
                    query.principal().tenantId().value(),
                    traceFailure.getClass().getSimpleName()
            );
        }
        boolean executionDegraded = degraded(routedSpaces, warnings);
        boolean executionFailed = terminalStatus == RetrievalTerminalStatus.CHECK_FAILED
                || terminalStatus == RetrievalTerminalStatus.TECHNICAL_FAILED;
        EvidenceBundle bundle = new EvidenceBundle(
                query.requestId(),
                traceId,
                query.principal().tenantId(),
                evidence,
                terminalStatus,
                stopReason,
                executionDegraded,
                visitedSpaces,
                configurationFingerprints,
                warnings,
                completedAt
        );
        observations.emit(
                new RetrievalObservationPayload.ExecutionTerminal(
                        terminalStatus,
                        stopReason,
                        consumedRetrievalAttempts,
                        evidence.size(),
                        executionDegraded,
                        executionDegraded ? stableDegradationReasons(routedSpaces, warnings)
                                : List.of()
                ),
                executionFailed
                        ? RetrievalObservationStatus.FAILED
                        : executionDegraded
                                ? RetrievalObservationStatus.DEGRADED
                                : RetrievalObservationStatus.SUCCEEDED,
                executionFailed
                        ? terminalStatus.name()
                        : executionDegraded ? "DEGRADED_RETRIEVAL" : "NONE",
                startedAt
        );
        return bundle;
        } catch (RuntimeException failure) {
            observations.failedActiveStage();
            observations.emit(
                    new RetrievalObservationPayload.ExecutionTerminal(
                            RetrievalTerminalStatus.TECHNICAL_FAILED,
                            RetrievalStopReason.TECHNICAL_FAILURE,
                            consumedRetrievalAttempts,
                            0,
                            false,
                            List.of()
                    ),
                    RetrievalObservationStatus.FAILED,
                    "TECHNICAL_FAILURE",
                    startedAt
            );
            throw failure;
        }
    }

    /** 发布当前 Space 实际生效的配置修订与可安全审计的组件合同。 */
    private void emitConfigurationResolved(
            RetrievalObservationEmitter observations,
            EffectiveRetrievalConfiguration effective,
            ResolvedRetrievalComponents resolved,
            ActiveIndexGeneration activeIndexGeneration,
            Instant startedAt
    ) {
        List<RetrievalObservationPayload.ComponentVersion> components = new ArrayList<>();
        components.add(component("query-analyzer", "runtime", "deterministic", "v1"));
        components.add(component("retrieval-planner", "runtime", "rule-only", "v1"));
        components.add(component("fusion", "runtime", "weighted-rrf", "v1"));
        components.add(component(resolved.terminologyService().version()));
        components.add(component(resolved.feedbackQueryPlanner().version()));
        components.add(component(resolved.reranker().version()));
        components.add(component(resolved.coverageJudge().version()));
        components.add(component(
                "optimization-chain", "runtime", "fixed-order", "v1"
        ));
        resolved.retrieverVersions().values().stream()
                .sorted(java.util.Comparator.comparing(RetrievalComponentVersion::component))
                .map(DefaultKnowledgeGateway::component)
                .forEach(components::add);
        observations.emit(
                new RetrievalObservationPayload.ConfigurationResolved(
                        effective.spaceId(),
                        effective.sourceRevision(),
                        List.copyOf(components),
                        List.of(new RetrievalObservationPayload.DataIndexVersion(
                                activeIndexGeneration.spaceId(),
                                activeIndexGeneration.generationId(),
                                activeIndexGeneration.configurationVersion()
                        ))
                ),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                startedAt
        );
    }

    /** 只把稳定原因码写入终态事件，避免重复和动态异常文本进入指标维度。 */
    private List<String> stableDegradationReasons(
            SpaceRoutingStage.Result routedSpaces,
            List<String> warnings
    ) {
        java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
        if (routedSpaces.degraded()) {
            reasons.add("SPACE_ROUTER_FALLBACK");
        }
        warnings.stream().filter(value ->
                value.endsWith("_UNAVAILABLE")
                        || value.endsWith("_TIMEOUT")
                        || value.endsWith("_OVERLOADED")
                        || value.endsWith("_FAILED")
                        || value.endsWith("_RETRIED")
                        || "RETRIEVAL_DEADLINE_EXCEEDED".equals(value)
        ).forEach(reasons::add);
        return List.copyOf(reasons);
    }

    /** 读取当前 Space 的不可变配置修订并记录解析阶段。 */
    private EffectiveRetrievalConfiguration resolveConfiguration(
            KnowledgeQuery query,
            KnowledgeSpaceId spaceId,
            List<RetrievalStepTrace> steps
    ) {
        return measure(
                "CONFIGURATION_RESOLVED",
                1,
                steps,
                () -> spaceConfigurationStage.resolve(query, spaceId)
        );
    }

    /** 读取真实活动索引代际；尚未投影完成的 Space 不能以伪造版本进入检索。 */
    private ActiveIndexGeneration requireActiveIndexGeneration(
            KnowledgeQuery query,
            KnowledgeSpaceId spaceId
    ) {
        return activeIndexGenerationCatalog.findActiveGeneration(
                query.principal().tenantId(),
                spaceId
        ).orElseThrow(() -> new IllegalStateException(
                "knowledge space has no active index generation"
        ));
    }

    /**
     * 执行一次真正消耗 maxRetrievalAttempts 的 Retrieval Plan。
     *
     * <p>首轮计划固定包含 Q0 和可选术语增强；同一 Space、相同约束和配置下，
     * 反馈轮只执行 Chain 产生的局部 Variant。Q0 仍作为精排和 Coverage 的全局语义锚点，
     * 但不重复消耗物理召回资源。</p>
     */
    private List<FusedCandidate> executeAttempt(
            KnowledgeQuery query,
            AccessScope scope,
            RetrievalConfiguration configuration,
            ResolvedRetrievalComponents resolvedComponents,
            ActiveIndexGeneration activeIndexGeneration,
            QueryOptimizationPlan feedbackPlan,
            ResolvedConstraints resolvedConstraints,
            Instant deadline,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos,
            RetrievalObservationEmitter observations
    ) {
        Instant queryAnalysisStartedAt = clock.instant();
        observations.beginStage("QUERY_ANALYSIS", 1, queryAnalysisStartedAt);
        QueryPlan plan = measure(
                "QUERY_ANALYSIS",
                1,
                steps,
                () -> queryAnalyzer.analyze(query)
        );
        observations.emit(
                new RetrievalObservationPayload.QueryAnalysisCompleted(
                        observations.fingerprint(plan.normalizedQuery()),
                        plan.channels(),
                        plan.candidateLimit(),
                        component("query-analyzer", "runtime", "deterministic", "v1")
                ),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                queryAnalysisStartedAt
        );
        Instant queryPlanningStartedAt = clock.instant();
        observations.beginStage("QUERY_PLANNING", 1, queryPlanningStartedAt);
        int warningCountBeforePlanning = warnings.size();
        QueryPlanningStage.Result queryPlanningResult = feedbackPlan == null
                ? new QueryPlanningStage(
                        resolvedComponents.terminologyService().implementation(),
                        executor,
                        clock,
                        queryPlannerTimeout
                ).planWithFallback(
                        query,
                        plan,
                        resolvedConstraints,
                        configuration.firstRound(),
                        configuration.branches().maximumVariantsPerAttempt(),
                        steps,
                        warnings,
                        requestDeadlineNanos
                )
                : new QueryPlanningStage.Result(feedbackPlan, 0);
        QueryOptimizationPlan optimizationPlan = queryPlanningResult.plan();
        boolean queryPlanningDegraded = warnings.subList(
                Math.min(warningCountBeforePlanning, warnings.size()),
                warnings.size()
        ).stream().anyMatch(value -> value.startsWith("TERMINOLOGY_SERVICE_"));
        observations.emit(
                new RetrievalObservationPayload.QueryPlanningCompleted(
                        planningComponent(resolvedComponents, optimizationPlan),
                        optimizationPlan.variants().stream().map(value ->
                                new RetrievalObservationPayload.QueryVariantFact(
                                        value.id(),
                                        value.kind().name(),
                                        observations.fingerprint(value.text())
                                )
                        ).toList(),
                        queryPlanningResult.terminologyCallCount() > 0
                                ? RetrievalObservationPayload.UsageCount.unmeasured(
                                        queryPlanningResult.terminologyCallCount()
                                )
                                : RetrievalObservationPayload.UsageCount.none()
                ),
                queryPlanningDegraded
                        ? RetrievalObservationStatus.DEGRADED
                        : RetrievalObservationStatus.SUCCEEDED,
                queryPlanningDegraded ? "TERMINOLOGY_SERVICE_FALLBACK" : "NONE",
                queryPlanningStartedAt
        );
        Instant retrievalPlanStartedAt = clock.instant();
        observations.beginStage(
                "RETRIEVAL_PLAN",
                optimizationPlan.variants().size(),
                retrievalPlanStartedAt
        );
        List<RetrievalBranchRequest> requests = retrievalPlanner.plan(
                query,
                plan,
                optimizationPlan,
                configuration,
                resolvedComponents.retrieverVersions().keySet(),
                scope,
                deadline,
                warnings
        );
        String attemptStrategy = switch (optimizationPlan.kind()) {
            case FIRST_ROUND -> "FIRST_ROUND";
            case QUERY_FEEDBACK -> optimizationPlan.variants().getFirst().kind().name();
            case CONSTRAINT_CHANGE -> "CONSTRAINT_CHANGE";
        };
        observations.emit(
                new RetrievalObservationPayload.RetrievalPlanCompleted(
                        attemptStrategy,
                        requests.stream().map(value ->
                                new RetrievalObservationPayload.RetrievalBranchPlan(
                                        value.branchId(),
                                        value.queryVariant().id(),
                                        value.request().plan().channels().iterator().next(),
                                        value.request().query().spaceIds().iterator().next(),
                                        activeIndexGeneration.configurationVersion(),
                                        value.request().plan().candidateLimit()
                                )
                        ).toList()
                ),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                retrievalPlanStartedAt
        );
        Instant retrievalBranchStartedAt = clock.instant();
        observations.beginStage(
                "RETRIEVAL_BRANCH",
                requests.size(),
                retrievalBranchStartedAt
        );
        RetrievalFanout.Result fanoutResult = new RetrievalFanout(
                resolvedComponents.retrievers(),
                executor,
                clock,
                channelTimeout
        ).retrieveAll(
                requests,
                steps,
                warnings,
                requestDeadlineNanos
        );
        List<RankedCandidateList> rankedLists = fanoutResult.rankedLists();
        List<RetrievalCandidate> candidates = rankedLists.stream()
                .flatMap(value -> value.candidates().stream())
                .toList();
        List<RankedCandidateList> activeRankedLists;
        List<RetrievalCandidate> activeCandidates;
        try {
            ensureAuthorizedCandidates(scope, candidates);
            activeRankedLists = rankedLists.stream()
                    .map(value -> new RankedCandidateList(
                            value.branchId(),
                            value.channel(),
                            value.weight(),
                            List.copyOf(Objects.requireNonNull(
                                    activeRevisionGuard.retainActive(
                                            query.principal().tenantId(),
                                            value.candidates()
                                    ),
                                    "activeRevisionGuard must not return null"
                            ))
                    ))
                    .toList();
            activeCandidates = activeRankedLists.stream()
                    .flatMap(value -> value.candidates().stream())
                    .toList();
            ensureAuthorizedCandidates(scope, activeCandidates);
        } catch (RuntimeException validationFailure) {
            // Retriever 已经执行，因此即使后置安全校验拒绝结果，也必须先闭合每个物理分支。
            // 失败事件刻意不携带候选身份，避免把未授权结果写入观测存储。
            emitRetrievalBranches(
                    observations,
                    fanoutResult,
                    List.of(),
                    attemptStrategy,
                    resolvedComponents,
                    activeIndexGeneration,
                    true
            );
            throw validationFailure;
        }
        emitRetrievalBranches(
                observations,
                fanoutResult,
                activeRankedLists,
                attemptStrategy,
                resolvedComponents,
                activeIndexGeneration,
                false
        );
        Instant fusionStartedAt = clock.instant();
        observations.beginStage("FUSION", activeCandidates.size(), fusionStartedAt);
        List<FusedCandidate> fusedPool = measure(
                "RRF_FUSION",
                activeCandidates.size(),
                steps,
                () -> new ReciprocalRankFusion(
                        configuration.branches().rrfConstant()
                ).fuseRankedLists(
                        activeRankedLists,
                        configuration.reranker().candidateLimit()
                )
        );
        observations.emit(
                new RetrievalObservationPayload.FusionCompleted(
                        attemptStrategy,
                        component("fusion", "runtime", "weighted-rrf", "v1"),
                        activeCandidates.size(),
                        (int) activeCandidates.stream()
                                .map(RetrievalCandidate::chunkId)
                                .distinct()
                                .count(),
                        configuration.branches().rrfConstant(),
                        fusedFacts(fusedPool, activeRankedLists)
                ),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                fusionStartedAt
        );
        Instant rerankStartedAt = clock.instant();
        observations.beginStage("RERANK", fusedPool.size(), rerankStartedAt);
        RerankStage.Result rerankResult = new RerankStage(
                resolvedComponents.reranker().implementation(),
                executor,
                clock,
                rerankerTimeout
        ).rerankWithFallback(
                query,
                compileRerankQuery(query.text(), optimizationPlan),
                fusedPool,
                configuration.reranker().enabled(),
                configuration.reranker().candidateLimit(),
                configuration.reranker().outputTopK(),
                values -> ensureAuthorizedCandidates(scope, values),
                steps,
                warnings,
                requestDeadlineNanos
        );
        emitRerankCompleted(
                observations,
                configuration,
                resolvedComponents.reranker().version(),
                attemptStrategy,
                fusedPool,
                rerankResult,
                rerankStartedAt
        );
        return rerankResult.candidates();
    }

    /**
     * 根据当前 Retrieval Plan 编译精排查询，不建立第二套“证据精排目标”。
     *
     * <p>HyDE 的假想内容只用于向量定位，不参与精排语义；其他反馈策略使用
     * “Q0 主体 + 本轮局部目标”。结果做码点级硬上限，避免不同 Reranker Adapter
     * 对超长输入产生不一致行为。</p>
     */
    private String compileRerankQuery(
            String originalQuery,
            QueryOptimizationPlan optimizationPlan
    ) {
        if (optimizationPlan.kind() == QueryOptimizationPlan.Kind.FIRST_ROUND
                || optimizationPlan.kind() == QueryOptimizationPlan.Kind.CONSTRAINT_CHANGE
                || optimizationPlan.variants().getFirst().kind() == QueryVariantKind.HYDE) {
            return originalQuery;
        }
        String compiled = originalQuery + "\n当前轮检索目标："
                + optimizationPlan.variants().getFirst().text();
        int maximumCodePoints = 16_000;
        if (compiled.codePointCount(0, compiled.length()) <= maximumCodePoints) {
            return compiled;
        }
        return compiled.substring(0, compiled.offsetByCodePoints(0, maximumCodePoints));
    }

    /** Coverage 被显式关闭或缺少证据要求时仍发布一条可解释的跳过事件。 */
    private void emitCoverageSkipped(
            RetrievalObservationEmitter observations,
            RetrievalConfiguration configuration,
            RetrievalComponentVersion componentVersion,
            int retainedCandidateCount,
            int requirementCount,
            RetrievalObservationPayload.CoverageTerminalStatus terminalStatus,
            String reasonCode
    ) {
        observations.emit(
                new RetrievalObservationPayload.CoverageCheckCompleted(
                        component(componentVersion),
                        retainedCandidateCount,
                        retainedCandidateCount,
                        requirementCount,
                        RetrievalObservationPayload.OptionalCount.absent(),
                        RetrievalObservationPayload.OptionalScore.absent(),
                        RetrievalObservationPayload.OptionalScore.absent(),
                        terminalStatus,
                        reasonCode,
                        RetrievalObservationPayload.UsageCount.none()
                ),
                RetrievalObservationStatus.SKIPPED,
                reasonCode,
                clock.instant()
        );
    }

    /** 发布 Coverage Judge 的真实结果；未返回的逐要求覆盖数保持显式缺失。 */
    private void emitCoverageCompleted(
            RetrievalObservationEmitter observations,
            RetrievalConfiguration configuration,
            RetrievalComponentVersion componentVersion,
            int requirementCount,
            int evaluatedCandidateCount,
            CoverageStage.Result result,
            RetrievalObservationPayload.CoverageTerminalStatus terminalStatus,
            Instant startedAt
    ) {
        String stopReason = switch (terminalStatus) {
            case SUFFICIENT -> "SUFFICIENCY_THRESHOLD_REACHED";
            case INSUFFICIENT -> "RETRIEVAL_BUDGET_EXHAUSTED";
            case CONTINUE -> "CONTINUE";
            case CHECK_FAILED -> "COVERAGE_CHECK_FAILED";
            default -> terminalStatus.name();
        };
        observations.emit(
                new RetrievalObservationPayload.CoverageCheckCompleted(
                        component(componentVersion),
                        evaluatedCandidateCount,
                        result.retainedCandidates().size(),
                        requirementCount,
                        RetrievalObservationPayload.OptionalCount.absent(),
                        result.checkFailed()
                                ? RetrievalObservationPayload.OptionalScore.absent()
                                : RetrievalObservationPayload.OptionalScore.of(
                                        result.coverage()
                                ),
                        RetrievalObservationPayload.OptionalScore.of(
                                configuration.coverage().sufficiencyThreshold()
                        ),
                        terminalStatus,
                        stopReason,
                        result.requestCount() > 0
                                ? RetrievalObservationPayload.UsageCount.unmeasured(
                                        result.requestCount()
                                )
                                : RetrievalObservationPayload.UsageCount.none()
                ),
                result.checkFailed()
                        ? RetrievalObservationStatus.FAILED
                        : RetrievalObservationStatus.SUCCEEDED,
                result.checkFailed() ? "COVERAGE_CHECK_FAILED" : "NONE",
                startedAt
        );
    }

    private int uniqueCandidateCount(
            List<FusedCandidate> previousMemory,
            List<FusedCandidate> currentCandidates
    ) {
        return (int) java.util.stream.Stream.concat(
                        previousMemory.stream(),
                        currentCandidates.stream()
                )
                .map(value -> value.representative().chunkId())
                .distinct()
                .count();
    }

    /** 把一个 Chain 节点与其下一轮 Coverage 结果闭合，供增量收益指标计算。 */
    private void completePendingChain(
            RetrievalObservationEmitter observations,
            PendingChainObservation pending,
            int outputCandidateCount,
            RetrievalObservationPayload.OptionalScore coverageAfter,
            boolean degraded,
            String resultReason
    ) {
        if (pending == null) {
            return;
        }
        observations.emit(
                new RetrievalObservationPayload.ChainNodeCompleted(
                        pending.node().name(),
                        pending.strategy(),
                        resultReason,
                        pending.inputCandidateCount(),
                        outputCandidateCount,
                        pending.coverageBefore(),
                        coverageAfter,
                        pending.usage()
                ),
                degraded
                        ? RetrievalObservationStatus.DEGRADED
                        : RetrievalObservationStatus.SUCCEEDED,
                degraded ? resultReason : "NONE",
                pending.startedAt()
        );
    }

    /** 发布每个物理召回分支的终态；失败分支也必须形成一条事件。 */
    private void emitRetrievalBranches(
            RetrievalObservationEmitter observations,
            RetrievalFanout.Result fanoutResult,
            List<RankedCandidateList> acceptedRankedLists,
            String strategy,
            ResolvedRetrievalComponents resolvedComponents,
            ActiveIndexGeneration activeIndexGeneration,
            boolean outputValidationFailed
    ) {
        Map<String, RankedCandidateList> acceptedByBranch = acceptedRankedLists.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        RankedCandidateList::branchId,
                        value -> value
                ));
        for (RetrievalFanout.BranchOutcome outcome : fanoutResult.outcomes()) {
            RetrievalBranchRequest branch = outcome.branch();
            var channel = branch.request().plan().channels().iterator().next();
            RankedCandidateList accepted = acceptedByBranch.get(branch.branchId());
            List<RetrievalCandidate> candidates = accepted == null
                    ? List.of() : accepted.candidates();
            RetrievalObservationStatus status = outputValidationFailed && outcome.succeeded()
                    ? RetrievalObservationStatus.FAILED
                    : branchObservationStatus(outcome.status());
            observations.emit(
                    new RetrievalObservationPayload.RetrievalBranchCompleted(
                            branch.branchId(),
                            strategy,
                            branch.queryVariant().id(),
                            observations.fingerprint(branch.queryVariant().text()),
                            channel,
                            component(resolvedComponents.retrieverVersion(channel)),
                            activeIndexGeneration.configurationVersion(),
                            branch.request().plan().candidateLimit(),
                            outcome.succeeded()
                                    ? branch.request().plan().candidateLimit() : 0,
                            java.util.stream.IntStream.range(0, candidates.size())
                                    .mapToObj(index ->
                                            new RetrievalObservationPayload.RankedCandidate(
                                                    candidateIdentity(candidates.get(index)),
                                                    index + 1,
                                                    candidates.get(index).score()
                                            )
                                    ).toList()
                    ),
                    status,
                    outputValidationFailed && outcome.succeeded()
                            ? "RETRIEVER_OUTPUT_VALIDATION_FAILED"
                            : status == RetrievalObservationStatus.SUCCEEDED
                                    ? "NONE" : "RETRIEVER_" + outcome.status(),
                    clock.instant().minus(outcome.duration())
            );
        }
    }

    /** 把融合事实映射为不含正文、但保留每个分支名次贡献的事件载荷。 */
    private List<RetrievalObservationPayload.FusedCandidateFact> fusedFacts(
            List<FusedCandidate> fusedCandidates,
            List<RankedCandidateList> rankedLists
    ) {
        Map<String, RankedCandidateList> byBranch = rankedLists.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        RankedCandidateList::branchId,
                        value -> value
                )
        );
        return java.util.stream.IntStream.range(0, fusedCandidates.size())
                .mapToObj(index -> {
                    FusedCandidate fused = fusedCandidates.get(index);
                    List<RetrievalObservationPayload.RankContribution> contributions =
                            fused.contributionsByBranch().entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(entry -> {
                                        RankedCandidateList source = Objects.requireNonNull(
                                                byBranch.get(entry.getKey()),
                                                "fusion contribution branch is missing"
                                        );
                                        int sourceRank = sourceRank(
                                                source,
                                                fused.representative().chunkId()
                                        );
                                        return new RetrievalObservationPayload.RankContribution(
                                                entry.getKey(),
                                                source.channel(),
                                                sourceRank,
                                                entry.getValue()
                                        );
                                    }).toList();
                    return new RetrievalObservationPayload.FusedCandidateFact(
                            candidateIdentity(fused.representative()),
                            index + 1,
                            fused.normalizedRrfScore(),
                            contributions
                    );
                }).toList();
    }

    /** 发布精排执行、禁用或整批 RRF 回退事实。 */
    private void emitRerankCompleted(
            RetrievalObservationEmitter observations,
            RetrievalConfiguration configuration,
            RetrievalComponentVersion componentVersion,
            String strategy,
            List<FusedCandidate> input,
            RerankStage.Result result,
            Instant startedAt
    ) {
        Map<UUID, Integer> inputRanks = java.util.stream.IntStream.range(0, input.size())
                .boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        index -> input.get(index).representative().chunkId(),
                        index -> index + 1
                ));
        List<RetrievalObservationPayload.RerankedCandidateFact> facts =
                java.util.stream.IntStream.range(0, result.candidates().size())
                        .mapToObj(index -> {
                            FusedCandidate value = result.candidates().get(index);
                            Integer inputRank = Objects.requireNonNull(
                                    inputRanks.get(value.representative().chunkId()),
                                    "reranker returned a candidate outside its input"
                            );
                            return new RetrievalObservationPayload.RerankedCandidateFact(
                                    candidateIdentity(value.representative()),
                                    inputRank,
                                    index + 1,
                                    value.rerankScore() == null
                                            ? RetrievalObservationPayload.OptionalScore.absent()
                                            : RetrievalObservationPayload.OptionalScore.of(
                                                    value.rerankScore()
                                            )
                            );
                        }).toList();
        RetrievalObservationStatus status = result.fallback()
                ? RetrievalObservationStatus.DEGRADED
                : result.executed()
                        ? RetrievalObservationStatus.SUCCEEDED
                        : RetrievalObservationStatus.SKIPPED;
        String reasonCode = switch (status) {
            case SUCCEEDED -> "NONE";
            case DEGRADED -> "RERANKER_FALLBACK";
            default -> "RERANKER_" + result.reasonCode();
        };
        observations.emit(
                new RetrievalObservationPayload.RerankCompleted(
                        strategy,
                        component(componentVersion),
                        result.executed(),
                        result.fallback(),
                        input.size(),
                        facts,
                        result.executed()
                                ? RetrievalObservationPayload.UsageCount.unmeasured(1)
                                : RetrievalObservationPayload.UsageCount.none()
                ),
                status,
                reasonCode,
                startedAt
        );
    }

    private RetrievalObservationPayload.CandidateIdentity candidateIdentity(
            RetrievalCandidate candidate
    ) {
        return new RetrievalObservationPayload.CandidateIdentity(
                candidate.chunkId(),
                candidate.documentId().value(),
                candidate.revisionId(),
                candidate.spaceId()
        );
    }

    private int sourceRank(RankedCandidateList source, UUID chunkId) {
        for (int index = 0; index < source.candidates().size(); index++) {
            if (source.candidates().get(index).chunkId().equals(chunkId)) {
                return index + 1;
            }
        }
        throw new IllegalStateException("fusion contribution candidate is missing from branch");
    }

    private RetrievalObservationStatus branchObservationStatus(String status) {
        return switch (status) {
            case "SUCCEEDED" -> RetrievalObservationStatus.SUCCEEDED;
            case "TIMEOUT", "DEADLINE_EXCEEDED" -> RetrievalObservationStatus.TIMED_OUT;
            case "REJECTED" -> RetrievalObservationStatus.REJECTED;
            case "CANCELLED" -> RetrievalObservationStatus.CANCELLED;
            case "NOT_CONFIGURED" -> RetrievalObservationStatus.NOT_CONFIGURED;
            default -> RetrievalObservationStatus.FAILED;
        };
    }

    /** 清洗第三方组件标识，既不泄露端点，也不让观测字段破坏业务执行。 */
    private static RetrievalObservationPayload.ComponentVersion component(
            String name,
            String provider,
            String model,
            String version
    ) {
        return new RetrievalObservationPayload.ComponentVersion(
                safeIdentifier(name, 64),
                safeIdentifier(provider, 64),
                safeIdentifier(model, 128),
                safeIdentifier(version, 128)
        );
    }

    /** 把与执行实例绑定的 SPI 合同无损映射为观测合同。 */
    private static RetrievalObservationPayload.ComponentVersion component(
            RetrievalComponentVersion version
    ) {
        Objects.requireNonNull(version, "version must not be null");
        return component(
                version.component(),
                version.provider(),
                version.model(),
                version.version()
        );
    }

    /** 查询规划事件始终引用本轮真正执行的组件，而不是 Variant 自报标签。 */
    private static RetrievalObservationPayload.ComponentVersion planningComponent(
            ResolvedRetrievalComponents resolved,
            QueryOptimizationPlan plan
    ) {
        return switch (plan.kind()) {
            case FIRST_ROUND -> component(resolved.terminologyService().version());
            case QUERY_FEEDBACK -> component(resolved.feedbackQueryPlanner().version());
            case CONSTRAINT_CHANGE -> component(
                    "constraint-optimizer", "runtime", "deterministic", "v1"
            );
        };
    }

    private static String safeIdentifier(String value, int maximumLength) {
        String normalized = Objects.requireNonNull(value, "component identifier must not be null")
                .replaceAll("[^A-Za-z0-9._:/-]", "-");
        if (normalized.isEmpty() || !Character.isLetterOrDigit(normalized.charAt(0))) {
            normalized = "x-" + normalized;
        }
        return normalized.substring(0, Math.min(maximumLength, normalized.length()));
    }

    /** 只在调用方提供要求或明确检索目标时建立 Coverage 检查项。 */
    private List<EvidenceRequirement> resolveEvidenceRequirements(KnowledgeQuery query) {
        if (!query.evidenceRequirements().isEmpty()) {
            return query.evidenceRequirements();
        }
        if (!query.retrievalTarget().isBlank()) {
            return List.of(new EvidenceRequirement(
                    "retrieval-target",
                    query.retrievalTarget()
            ));
        }
        return List.of();
    }

    /** 复制查询并注入 Runtime 的最小 Requirement 兜底结果。 */
    private KnowledgeQuery withEvidenceRequirements(
            KnowledgeQuery query,
            List<EvidenceRequirement> requirements
    ) {
        return new KnowledgeQuery(
                query.requestId(),
                query.principal(),
                query.text(),
                query.spaceIds(),
                query.topK(),
                query.filters(),
                query.constraints(),
                query.retrievalTarget(),
                requirements,
                query.configurationOverride(),
                query.purpose()
        );
    }

    /** 合并跨 Space 的有限 Memory 和当前结果，并保持稳定顺序与 Chunk 去重。 */
    private List<FusedCandidate> mergeEvidenceMemory(
            List<FusedCandidate> memory,
            List<FusedCandidate> current,
            int limit
    ) {
        java.util.LinkedHashMap<UUID, FusedCandidate> merged = new java.util.LinkedHashMap<>();
        memory.forEach(value -> merged.put(value.representative().chunkId(), value));
        current.forEach(value -> merged.putIfAbsent(value.representative().chunkId(), value));
        return merged.values().stream().limit(limit).toList();
    }

    /** 把固定 Chain 节点映射到唯一允许的查询变体类型。 */
    private java.util.Optional<QueryVariantKind> variantKind(
            RetrievalConfiguration.ChainNode node
    ) {
        return switch (node) {
            case GAP_QUERY -> java.util.Optional.of(QueryVariantKind.GAP_QUERY);
            case PRF -> java.util.Optional.of(QueryVariantKind.PRF);
            case STEP_BACK -> java.util.Optional.of(QueryVariantKind.STEP_BACK);
            case HYDE -> java.util.Optional.of(QueryVariantKind.HYDE);
            case RELAX_CONSTRAINTS, NARROW_CONSTRAINTS, NEXT_SPACE ->
                    java.util.Optional.empty();
        };
    }

    /** 判断所有启用节点是否都已成功执行或被确认不可执行。 */
    private boolean chainExhausted(
            RetrievalConfiguration configuration,
            Set<RetrievalConfiguration.ChainNode> consumed
    ) {
        return configuration.chainNodeEnables().entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .allMatch(consumed::contains);
    }

    /** 显式配置旁路不是降级；只有运行时警告或模型路由回退才标记 degraded。 */
    private boolean degraded(
            SpaceRoutingStage.Result routedSpaces,
            List<String> warnings
    ) {
        return routedSpaces.degraded() || warnings.stream().anyMatch(value ->
                value.endsWith("_UNAVAILABLE")
                        || value.endsWith("_TIMEOUT")
                        || value.endsWith("_OVERLOADED")
                        || value.endsWith("_FAILED")
                        || value.endsWith("_RETRIED")
                        || "RETRIEVAL_DEADLINE_EXCEEDED".equals(value));
    }

    /**
     * 验证基础设施返回值仍处于 Policy Engine 生成的租户和空间范围。
     *
     * @param scope 授权范围
     * @param candidates 候选列表
     */
    private void ensureAuthorizedCandidates(
            AccessScope scope,
            List<RetrievalCandidate> candidates
    ) {
        for (RetrievalCandidate candidate : candidates) {
            if (!scope.tenantId().equals(candidate.tenantId())
                    || !scope.allowsSpace(candidate.spaceId())) {
                throw new SecurityException(
                        "retriever returned candidate outside authorized scope"
                );
            }
            if (!scope.allowsDocument(candidate.documentId().value().toString())) {
                throw new SecurityException(
                        "retriever returned document outside authorized scope"
                );
            }
        }
    }

    /**
     * 在查询分析或任何 Retriever 执行前校验策略结果。
     *
     * <p>Policy 只能收窄调用方请求，不能改变租户或扩展到未请求的空间。</p>
     */
    private void validateAccessScope(KnowledgeQuery query, AccessScope scope) {
        Objects.requireNonNull(scope, "accessPolicy must not return null");
        if (!query.principal().tenantId().equals(scope.tenantId())) {
            throw new SecurityException("access policy returned a different tenant");
        }
        if (scope.deniesAll()) {
            throw new KnowledgeAccessDeniedException("No readable knowledge space");
        }
        if (!query.spaceIds().isEmpty()
                && !query.spaceIds().containsAll(scope.spaceIds())) {
            throw new SecurityException("access policy expanded the requested spaces");
        }
    }

    /** 将独立查询收窄到路由列表当前下标指向的 Space。 */
    private KnowledgeQuery forSpace(
            KnowledgeQuery query,
            KnowledgeSpaceId spaceId
    ) {
        return new KnowledgeQuery(
                query.requestId(),
                query.principal(),
                query.text(),
                java.util.Set.of(spaceId),
                query.topK(),
                query.filters(),
                query.constraints(),
                query.retrievalTarget(),
                query.evidenceRequirements(),
                query.configurationOverride(),
                query.purpose()
        );
    }

    /**
     * 记录同步阶段耗时和输出数量。
     *
     * @param name 阶段名称
     * @param inputCount 输入数量
     * @param steps Trace 集合
     * @param action 阶段动作
     * @param <T> 结果类型
     * @return 阶段结果
     */
    private <T> T measure(
            String name,
            int inputCount,
            List<RetrievalStepTrace> steps,
            java.util.function.Supplier<T> action
    ) {
        Instant startedAt = clock.instant();
        T result = action.get();
        int outputCount = result instanceof java.util.Collection<?> collection
                ? collection.size()
                : 1;
        steps.add(new RetrievalStepTrace(
                name,
                nonNegativeDuration(startedAt, clock.instant()),
                inputCount,
                outputCount,
                "SUCCEEDED"
        ));
        return result;
    }

    /**
     * 防止测试时钟或外部时钟回拨产生非法负耗时。
     *
     * @param start 开始时间
     * @param end 结束时间
     * @return 非负耗时
     */
    private Duration nonNegativeDuration(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private Duration requirePositiveTimeout(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name + " must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(name + " must be between 1ns and 10 minutes");
        }
        return timeout;
    }

    /** 等待下一轮检索与 Coverage 结果闭合的 Chain 节点事实。 */
    private record PendingChainObservation(
            RetrievalConfiguration.ChainNode node,
            String strategy,
            int inputCandidateCount,
            RetrievalObservationPayload.OptionalScore coverageBefore,
            RetrievalObservationPayload.UsageCount usage,
            Instant startedAt
    ) {
        private PendingChainObservation {
            Objects.requireNonNull(node, "node must not be null");
            Objects.requireNonNull(strategy, "strategy must not be null");
            if (inputCandidateCount < 0) {
                throw new IllegalArgumentException(
                        "inputCandidateCount must not be negative"
                );
            }
            Objects.requireNonNull(coverageBefore, "coverageBefore must not be null");
            Objects.requireNonNull(usage, "usage must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
        }
    }

}
