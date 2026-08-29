package dev.infinityknowledge.domain.retrieval.observation;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 检索观测事件的强类型载荷。
 *
 * <p>载荷只允许保存标识、指纹、排名、评分、计数、状态原因、版本和耗时所需事实。
 * 原查询、查询变体正文、知识正文、标题、来源 URI、Prompt 和模型原始响应不得进入本协议。</p>
 */
public sealed interface RetrievalObservationPayload permits
        RetrievalObservationPayload.ExecutionStarted,
        RetrievalObservationPayload.ConfigurationResolved,
        RetrievalObservationPayload.SpaceRoutingCompleted,
        RetrievalObservationPayload.QueryAnalysisCompleted,
        RetrievalObservationPayload.QueryPlanningCompleted,
        RetrievalObservationPayload.RetrievalPlanCompleted,
        RetrievalObservationPayload.RetrievalBranchCompleted,
        RetrievalObservationPayload.FusionCompleted,
        RetrievalObservationPayload.RerankCompleted,
        RetrievalObservationPayload.CoverageCheckCompleted,
        RetrievalObservationPayload.ChainNodeEvaluated,
        RetrievalObservationPayload.ChainNodeCompleted,
        RetrievalObservationPayload.SpaceChanged,
        RetrievalObservationPayload.EvidenceBuildCompleted,
        RetrievalObservationPayload.ExecutionTerminal,
        RetrievalObservationPayload.StageFailed {

    int MAX_CANDIDATES = 10_000;
    int MAX_VARIANTS = 16;

    /** 返回载荷对应的唯一阶段。 */
    RetrievalObservationStage stage();

    /** 返回本阶段实际接收的对象数量。 */
    int inputCount();

    /** 返回本阶段实际产出的对象数量。 */
    int outputCount();

    /** 检索执行开始时记录的安全请求摘要。 */
    record ExecutionStarted(
            TextFingerprint queryFingerprint,
            int requestedTopK,
            int requestedSpaceCount
    ) implements RetrievalObservationPayload {
        public ExecutionStarted {
            Objects.requireNonNull(queryFingerprint, "queryFingerprint must not be null");
            requirePositive(requestedTopK, "requestedTopK");
            requireCount(requestedSpaceCount, "requestedSpaceCount");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.EXECUTION_STARTED;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return 0;
        }
    }

    /** Space 配置、组件合同与数据索引版本完成解析后的不可变快照。 */
    record ConfigurationResolved(
            KnowledgeSpaceId spaceId,
            long sourceRevision,
            List<ComponentVersion> components,
            List<DataIndexVersion> dataIndexes
    ) implements RetrievalObservationPayload {
        public ConfigurationResolved {
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            if (sourceRevision < 0L) {
                throw new IllegalArgumentException("sourceRevision must not be negative");
            }
            components = immutableBounded(components, 64, "components");
            dataIndexes = immutableBounded(dataIndexes, 100, "dataIndexes");
            requireUniqueValues(components, "component version");
            requireUniqueValues(
                    dataIndexes.stream().map(DataIndexVersion::spaceId).toList(),
                    "data index spaceId"
            );
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.CONFIGURATION_RESOLVED;
        }

        @Override
        public int inputCount() {
            return components.size() + dataIndexes.size();
        }

        @Override
        public int outputCount() {
            return inputCount();
        }
    }

    /** Space Router 完成后记录的有序候选，不包含 Space 描述正文。 */
    record SpaceRoutingCompleted(
            int authorizedSpaceCount,
            List<RankedSpace> candidates
    ) implements RetrievalObservationPayload {
        public SpaceRoutingCompleted {
            requireCount(authorizedSpaceCount, "authorizedSpaceCount");
            candidates = immutableBounded(candidates, MAX_CANDIDATES, "candidates");
            requireUniqueRanks(candidates.stream().map(RankedSpace::rank).toList(), "space rank");
            requireUniqueValues(candidates.stream().map(RankedSpace::spaceId).toList(), "spaceId");
            if (candidates.size() > authorizedSpaceCount) {
                throw new IllegalArgumentException(
                        "space candidates must not exceed authorizedSpaceCount"
                );
            }
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.SPACE_ROUTING;
        }

        @Override
        public int inputCount() {
            return authorizedSpaceCount;
        }

        @Override
        public int outputCount() {
            return candidates.size();
        }
    }

    /** 确定性查询分析完成后的通道和预算摘要。 */
    record QueryAnalysisCompleted(
            TextFingerprint normalizedQueryFingerprint,
            Set<RetrievalChannel> channels,
            int candidateLimit,
            ComponentVersion componentVersion
    ) implements RetrievalObservationPayload {
        public QueryAnalysisCompleted {
            Objects.requireNonNull(
                    normalizedQueryFingerprint,
                    "normalizedQueryFingerprint must not be null"
            );
            channels = Set.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
            if (channels.isEmpty()) {
                throw new IllegalArgumentException("channels must not be empty");
            }
            requirePositive(candidateLimit, "candidateLimit");
            Objects.requireNonNull(componentVersion, "componentVersion must not be null");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.QUERY_ANALYSIS;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return channels.size();
        }
    }

    /** 查询规划完成后的有界变体指纹和模型使用量。 */
    record QueryPlanningCompleted(
            ComponentVersion componentVersion,
            List<QueryVariantFact> variants,
            UsageCount usage
    ) implements RetrievalObservationPayload {
        public QueryPlanningCompleted {
            Objects.requireNonNull(componentVersion, "componentVersion must not be null");
            variants = immutableBounded(variants, MAX_VARIANTS, "variants");
            requireUniqueValues(variants.stream().map(QueryVariantFact::id).toList(), "variant id");
            Objects.requireNonNull(usage, "usage must not be null");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.QUERY_PLANNING;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return variants.size();
        }
    }

    /** 一次尝试实际采用的有界物理召回计划。 */
    record RetrievalPlanCompleted(
            String strategy,
            List<RetrievalBranchPlan> branches
    ) implements RetrievalObservationPayload {
        public RetrievalPlanCompleted {
            strategy = requireCode(strategy, "strategy");
            branches = immutableBounded(branches, 128, "branches");
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("branches must not be empty");
            }
            requireUniqueValues(
                    branches.stream().map(RetrievalBranchPlan::branchId).toList(),
                    "branchId"
            );
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.RETRIEVAL_PLAN;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return branches.size();
        }
    }

    /** 单个查询变体与物理 Retriever 分支的召回结果。 */
    record RetrievalBranchCompleted(
            String branchId,
            String strategy,
            String variantId,
            TextFingerprint variantFingerprint,
            RetrievalChannel channel,
            ComponentVersion componentVersion,
            String dataIndexVersion,
            int requestedK,
            int effectiveK,
            List<RankedCandidate> candidates
    ) implements RetrievalObservationPayload {
        public RetrievalBranchCompleted {
            branchId = requireIdentifier(branchId, "branchId", 64);
            strategy = requireCode(strategy, "strategy");
            variantId = requireIdentifier(variantId, "variantId", 64);
            Objects.requireNonNull(variantFingerprint, "variantFingerprint must not be null");
            Objects.requireNonNull(channel, "channel must not be null");
            Objects.requireNonNull(componentVersion, "componentVersion must not be null");
            dataIndexVersion = requireIdentifier(
                    dataIndexVersion,
                    "dataIndexVersion",
                    128
            );
            requirePositive(requestedK, "requestedK");
            requireCount(effectiveK, "effectiveK");
            if (effectiveK > requestedK) {
                throw new IllegalArgumentException("effectiveK must not exceed requestedK");
            }
            candidates = rankedCandidates(candidates, "candidates");
            if (candidates.size() > effectiveK) {
                throw new IllegalArgumentException("candidates must not exceed effectiveK");
            }
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.RETRIEVAL_BRANCH;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return candidates.size();
        }
    }

    /** 去重与 RRF 融合完成后的来源贡献和有序候选。 */
    record FusionCompleted(
            String strategy,
            ComponentVersion componentVersion,
            int inputCandidateCount,
            int uniqueCandidateCount,
            int rankConstant,
            List<FusedCandidateFact> candidates
    ) implements RetrievalObservationPayload {
        public FusionCompleted {
            strategy = requireCode(strategy, "strategy");
            Objects.requireNonNull(componentVersion, "componentVersion must not be null");
            requireCount(inputCandidateCount, "inputCandidateCount");
            requireCount(uniqueCandidateCount, "uniqueCandidateCount");
            requirePositive(rankConstant, "rankConstant");
            if (uniqueCandidateCount > inputCandidateCount) {
                throw new IllegalArgumentException(
                        "uniqueCandidateCount must not exceed inputCandidateCount"
                );
            }
            candidates = immutableBounded(candidates, MAX_CANDIDATES, "candidates");
            requireUniqueRanks(
                    candidates.stream().map(FusedCandidateFact::rank).toList(),
                    "fusion rank"
            );
            requireUniqueValues(
                    candidates.stream().map(FusedCandidateFact::candidate).toList(),
                    "fusion candidate"
            );
            if (candidates.size() > uniqueCandidateCount) {
                throw new IllegalArgumentException(
                        "fusion candidates must not exceed uniqueCandidateCount"
                );
            }
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.FUSION;
        }

        @Override
        public int inputCount() {
            return inputCandidateCount;
        }

        @Override
        public int outputCount() {
            return candidates.size();
        }
    }

    /** Reranker 完成或稳定回退后的输入、输出名次和评分事实。 */
    record RerankCompleted(
            String strategy,
            ComponentVersion componentVersion,
            boolean executed,
            boolean fallback,
            int inputCandidateCount,
            List<RerankedCandidateFact> candidates,
            UsageCount usage
    ) implements RetrievalObservationPayload {
        public RerankCompleted {
            strategy = requireCode(strategy, "strategy");
            Objects.requireNonNull(componentVersion, "componentVersion must not be null");
            requireCount(inputCandidateCount, "inputCandidateCount");
            candidates = immutableBounded(candidates, MAX_CANDIDATES, "candidates");
            requireUniqueRanks(
                    candidates.stream().map(RerankedCandidateFact::outputRank).toList(),
                    "rerank output rank"
            );
            requireUniqueValues(
                    candidates.stream().map(RerankedCandidateFact::candidate).toList(),
                    "rerank candidate"
            );
            if (candidates.size() > inputCandidateCount) {
                throw new IllegalArgumentException(
                        "rerank candidates must not exceed inputCandidateCount"
                );
            }
            Objects.requireNonNull(usage, "usage must not be null");
            if (!executed && !usage.empty()) {
                throw new IllegalArgumentException(
                        "unexecuted reranker must not contain model usage"
                );
            }
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.RERANK;
        }

        @Override
        public int inputCount() {
            return inputCandidateCount;
        }

        @Override
        public int outputCount() {
            return candidates.size();
        }
    }

    /** Coverage Judge 一轮检查的结构化结果，不保存模型原始响应。 */
    record CoverageCheckCompleted(
            ComponentVersion componentVersion,
            int evaluatedCandidateCount,
            int retainedCandidateCount,
            int requirementCount,
            OptionalCount coveredRequirementCount,
            OptionalScore coverageScore,
            OptionalScore threshold,
            CoverageTerminalStatus terminalStatus,
            String stopReason,
            UsageCount usage
    ) implements RetrievalObservationPayload {
        public CoverageCheckCompleted {
            Objects.requireNonNull(componentVersion, "componentVersion must not be null");
            requireCount(evaluatedCandidateCount, "evaluatedCandidateCount");
            requireCount(retainedCandidateCount, "retainedCandidateCount");
            requireCount(requirementCount, "requirementCount");
            Objects.requireNonNull(
                    coveredRequirementCount,
                    "coveredRequirementCount must not be null"
            );
            if (retainedCandidateCount > evaluatedCandidateCount) {
                throw new IllegalArgumentException(
                        "retainedCandidateCount must not exceed evaluatedCandidateCount"
                );
            }
            if (coveredRequirementCount.present()
                    && coveredRequirementCount.value() > requirementCount) {
                throw new IllegalArgumentException(
                        "coveredRequirementCount must not exceed requirementCount"
                );
            }
            Objects.requireNonNull(coverageScore, "coverageScore must not be null");
            Objects.requireNonNull(threshold, "threshold must not be null");
            Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
            stopReason = requireCode(stopReason, "stopReason");
            Objects.requireNonNull(usage, "usage must not be null");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.COVERAGE_CHECK;
        }

        @Override
        public int inputCount() {
            return evaluatedCandidateCount;
        }

        @Override
        public int outputCount() {
            return retainedCandidateCount;
        }
    }

    /** 优化链节点完成适用性判断后的节点与稳定原因。 */
    record ChainNodeEvaluated(
            String node,
            boolean applicable,
            String reasonCode,
            int remainingAttemptCount
    ) implements RetrievalObservationPayload {
        public ChainNodeEvaluated {
            node = requireCode(node, "node");
            reasonCode = requireCode(reasonCode, "reasonCode");
            requireCount(remainingAttemptCount, "remainingAttemptCount");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.CHAIN_NODE_EVALUATED;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return applicable ? 1 : 0;
        }
    }

    /** 优化链节点实际执行后的增量计数与覆盖度变化。 */
    record ChainNodeCompleted(
            String node,
            String strategy,
            String reasonCode,
            int inputCandidateCount,
            int outputCandidateCount,
            OptionalScore coverageBefore,
            OptionalScore coverageAfter,
            UsageCount usage
    ) implements RetrievalObservationPayload {
        public ChainNodeCompleted {
            node = requireCode(node, "node");
            strategy = requireCode(strategy, "strategy");
            reasonCode = requireCode(reasonCode, "reasonCode");
            requireCount(inputCandidateCount, "inputCandidateCount");
            requireCount(outputCandidateCount, "outputCandidateCount");
            Objects.requireNonNull(coverageBefore, "coverageBefore must not be null");
            Objects.requireNonNull(coverageAfter, "coverageAfter must not be null");
            Objects.requireNonNull(usage, "usage must not be null");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.CHAIN_NODE_COMPLETED;
        }

        @Override
        public int inputCount() {
            return inputCandidateCount;
        }

        @Override
        public int outputCount() {
            return outputCandidateCount;
        }
    }

    /** NEXT_SPACE 等节点触发的 Space 切换，只保留前后标识和原因。 */
    record SpaceChanged(
            KnowledgeSpaceId fromSpaceId,
            KnowledgeSpaceId toSpaceId,
            String reasonCode
    ) implements RetrievalObservationPayload {
        public SpaceChanged {
            Objects.requireNonNull(fromSpaceId, "fromSpaceId must not be null");
            Objects.requireNonNull(toSpaceId, "toSpaceId must not be null");
            if (fromSpaceId.equals(toSpaceId)) {
                throw new IllegalArgumentException("fromSpaceId and toSpaceId must differ");
            }
            reasonCode = requireCode(reasonCode, "reasonCode");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.SPACE_CHANGED;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return 1;
        }
    }

    /** 最终证据构建完成后的候选标识和有序评分。 */
    record EvidenceBuildCompleted(
            int inputCandidateCount,
            List<RankedCandidate> evidences
    ) implements RetrievalObservationPayload {
        public EvidenceBuildCompleted {
            requireCount(inputCandidateCount, "inputCandidateCount");
            evidences = rankedCandidates(evidences, "evidences");
            if (evidences.size() > inputCandidateCount) {
                throw new IllegalArgumentException(
                        "evidences must not exceed inputCandidateCount"
                );
            }
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.EVIDENCE_BUILD;
        }

        @Override
        public int inputCount() {
            return inputCandidateCount;
        }

        @Override
        public int outputCount() {
            return evidences.size();
        }
    }

    /**
     * 整次检索结束时记录的业务终态、停止原因、实际尝试次数和稳定降级原因。
     *
     * <p>业务终态不能由事件技术状态反推：例如 INSUFFICIENT 是一次技术成功的正常返回，
     * CHECK_FAILED 才表示 Coverage 检查未得到可信结论。</p>
     */
    record ExecutionTerminal(
            RetrievalTerminalStatus terminalStatus,
            RetrievalStopReason stopReason,
            int retrievalAttemptCount,
            int resultCount,
            boolean degraded,
            List<String> degradationReasonCodes
    ) implements RetrievalObservationPayload {
        public ExecutionTerminal {
            Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
            Objects.requireNonNull(stopReason, "stopReason must not be null");
            validateTerminalReason(terminalStatus, stopReason);
            requireCount(retrievalAttemptCount, "retrievalAttemptCount");
            requireCount(resultCount, "resultCount");
            degradationReasonCodes = immutableBounded(
                    degradationReasonCodes,
                    64,
                    "degradationReasonCodes"
            ).stream().map(value -> requireCode(value, "degradationReasonCode")).toList();
            if (degraded != !degradationReasonCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "degraded must exactly match the presence of degradation reasons"
                );
            }
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.EXECUTION_TERMINAL;
        }

        @Override
        public int inputCount() {
            return 1;
        }

        @Override
        public int outputCount() {
            return resultCount;
        }

        private static void validateTerminalReason(
                RetrievalTerminalStatus terminalStatus,
                RetrievalStopReason stopReason
        ) {
            boolean valid = switch (terminalStatus) {
                case SUFFICIENT -> stopReason
                        == RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED;
                case INSUFFICIENT -> stopReason
                        == RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED
                        || stopReason == RetrievalStopReason.OPTIMIZATION_CHAIN_EXHAUSTED
                        || stopReason == RetrievalStopReason.NO_APPLICABLE_OPTIMIZATION_NODE;
                case NOT_EVALUATED -> stopReason == RetrievalStopReason.COVERAGE_DISABLED;
                case EVIDENCE_REQUIREMENTS_MISSING -> stopReason
                        == RetrievalStopReason.EVIDENCE_REQUIREMENTS_MISSING;
                case CHECK_FAILED -> stopReason == RetrievalStopReason.COVERAGE_CHECK_FAILED;
                case TECHNICAL_FAILED -> stopReason == RetrievalStopReason.TECHNICAL_FAILURE;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "terminalStatus and stopReason describe different outcomes"
                );
            }
        }
    }

    /**
     * 某个执行层在产出正常完成事件前发生技术失败。
     *
     * <p>只保存稳定组件码和安全计数，不保存异常消息、查询或模型响应。该事件先于整次执行的
     * 技术失败终态发布，使异步指标消费者能够定位失败层，同时仍以终态事件判断执行结果。</p>
     */
    record StageFailed(
            String failedComponent,
            int failedInputCount
    ) implements RetrievalObservationPayload {
        public StageFailed {
            failedComponent = requireCode(failedComponent, "failedComponent");
            requireCount(failedInputCount, "failedInputCount");
        }

        @Override
        public RetrievalObservationStage stage() {
            return RetrievalObservationStage.STAGE_FAILURE;
        }

        @Override
        public int inputCount() {
            return failedInputCount;
        }

        @Override
        public int outputCount() {
            return 0;
        }
    }

    /** 不可逆查询指纹；算法和密钥版本用于安全轮换，不保存原文本。 */
    record TextFingerprint(String algorithm, String keyVersion, String value) {
        public TextFingerprint {
            algorithm = requireCode(algorithm, "algorithm");
            keyVersion = requireIdentifier(keyVersion, "keyVersion", 64);
            value = DomainChecks.requiredText(value, "fingerprint", 128)
                    .toLowerCase(Locale.ROOT);
            if (!value.matches("[a-f0-9]{32,128}")) {
                throw new IllegalArgumentException(
                        "fingerprint must contain 32..128 lowercase hexadecimal characters"
                );
            }
        }
    }

    /** 可审计但不含端点、凭据或 Prompt 的组件实现版本。 */
    record ComponentVersion(
            String component,
            String provider,
            String model,
            String version
    ) {
        public ComponentVersion {
            component = requireIdentifier(component, "component", 64);
            provider = requireIdentifier(provider, "provider", 64);
            model = requireIdentifier(model, "model", 128);
            version = requireIdentifier(version, "version", 128);
        }
    }

    /** 一个 Space 当前参与召回的数据索引代际。 */
    record DataIndexVersion(
            KnowledgeSpaceId spaceId,
            UUID generationId,
            String version
    ) {
        public DataIndexVersion {
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(generationId, "generationId must not be null");
            version = requireIdentifier(version, "data index version", 128);
        }
    }

    /** 模型调用的安全计数；明确区分真实零值与 Provider 尚未返回 Token 计数。 */
    record UsageCount(
            int requestCount,
            boolean tokenCountsAvailable,
            int inputTokenCount,
            int outputTokenCount
    ) {
        public UsageCount {
            requireCount(requestCount, "requestCount");
            requireCount(inputTokenCount, "inputTokenCount");
            requireCount(outputTokenCount, "outputTokenCount");
            if (requestCount == 0 && (inputTokenCount != 0 || outputTokenCount != 0)) {
                throw new IllegalArgumentException(
                        "zero model requests must have zero token counts"
                );
            }
            if (!tokenCountsAvailable && (inputTokenCount != 0 || outputTokenCount != 0)) {
                throw new IllegalArgumentException(
                        "unavailable token counts must use zero placeholders"
                );
            }
        }

        /** 返回未调用模型时的零计数。 */
        public static UsageCount none() {
            return new UsageCount(0, true, 0, 0);
        }

        /** 返回已发生调用、但当前 Provider 合同尚未暴露 Token 计数的事实。 */
        public static UsageCount unmeasured(int requestCount) {
            if (requestCount < 1) {
                throw new IllegalArgumentException("requestCount must be positive");
            }
            return new UsageCount(requestCount, false, 0, 0);
        }

        /** 返回本阶段是否完全没有模型用量。 */
        public boolean empty() {
            return requestCount == 0;
        }
    }

    /** 一条不含查询正文的规划变体。 */
    record QueryVariantFact(String id, String kind, TextFingerprint fingerprint) {
        public QueryVariantFact {
            id = requireIdentifier(id, "variant id", 64);
            kind = requireCode(kind, "variant kind");
            Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        }
    }

    /** Retrieval Plan 中一个明确的查询变体与物理召回分支。 */
    record RetrievalBranchPlan(
            String branchId,
            String variantId,
            RetrievalChannel channel,
            KnowledgeSpaceId spaceId,
            String dataIndexVersion,
            int requestedK
    ) {
        public RetrievalBranchPlan {
            branchId = requireIdentifier(branchId, "branchId", 64);
            variantId = requireIdentifier(variantId, "variantId", 64);
            Objects.requireNonNull(channel, "channel must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            dataIndexVersion = requireIdentifier(
                    dataIndexVersion,
                    "dataIndexVersion",
                    128
            );
            requirePositive(requestedK, "requestedK");
        }
    }

    /** Coverage Check 对当前逻辑查询给出的有限终态。 */
    enum CoverageTerminalStatus {
        CONTINUE,
        SUFFICIENT,
        INSUFFICIENT,
        NOT_EVALUATED,
        EVIDENCE_REQUIREMENTS_MISSING,
        CHECK_FAILED
    }

    /** Space Router 返回的一条有序候选；当前协议不伪造模型未提供的分数。 */
    record RankedSpace(KnowledgeSpaceId spaceId, int rank) {
        public RankedSpace {
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            requirePositive(rank, "rank");
        }
    }

    /** 候选的租户内稳定标识，不携带任何知识展示字段。 */
    record CandidateIdentity(
            UUID chunkId,
            UUID documentId,
            UUID revisionId,
            KnowledgeSpaceId spaceId
    ) {
        public CandidateIdentity {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(revisionId, "revisionId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
        }
    }

    /** 一个阶段内的候选排名和阶段内评分。 */
    record RankedCandidate(CandidateIdentity candidate, int rank, double score) {
        public RankedCandidate {
            Objects.requireNonNull(candidate, "candidate must not be null");
            requirePositive(rank, "rank");
            score = DomainChecks.unitScore(score, "score");
        }
    }

    /** 单个分支对融合候选的可解释排名贡献。 */
    record RankContribution(
            String branchId,
            RetrievalChannel channel,
            int sourceRank,
            double contribution
    ) {
        public RankContribution {
            branchId = requireIdentifier(branchId, "branchId", 64);
            Objects.requireNonNull(channel, "channel must not be null");
            requirePositive(sourceRank, "sourceRank");
            if (!Double.isFinite(contribution) || contribution < 0.0D) {
                throw new IllegalArgumentException(
                        "contribution must be finite and non-negative"
                );
            }
        }
    }

    /** 融合后的候选及其来源贡献。 */
    record FusedCandidateFact(
            CandidateIdentity candidate,
            int rank,
            double score,
            List<RankContribution> contributions
    ) {
        public FusedCandidateFact {
            Objects.requireNonNull(candidate, "candidate must not be null");
            requirePositive(rank, "rank");
            score = DomainChecks.unitScore(score, "score");
            contributions = immutableBounded(contributions, 64, "contributions");
            if (contributions.isEmpty()) {
                throw new IllegalArgumentException("contributions must not be empty");
            }
            requireUniqueValues(
                    contributions.stream().map(RankContribution::branchId).toList(),
                    "contribution branchId"
            );
        }
    }

    /** 显式表达模型是否给出了候选评分，避免用零分冒充缺失值。 */
    record OptionalScore(boolean present, double value) {
        public OptionalScore {
            if (present) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "present score must be finite"
                    );
                }
            } else if (Double.compare(value, 0.0D) != 0) {
                throw new IllegalArgumentException("absent score value must be zero");
            }
        }

        /** 返回未评分状态。 */
        public static OptionalScore absent() {
            return new OptionalScore(false, 0.0D);
        }

        /** 返回一个只保证有限的阶段原始评分。 */
        public static OptionalScore of(double value) {
            return new OptionalScore(true, value);
        }
    }

    /** 显式表达某个计数是否由当前 Provider 合同真实返回。 */
    record OptionalCount(boolean present, int value) {
        public OptionalCount {
            requireCount(value, "optional count value");
            if (!present && value != 0) {
                throw new IllegalArgumentException("absent count value must be zero");
            }
        }

        /** 返回当前合同未提供该计数的状态。 */
        public static OptionalCount absent() {
            return new OptionalCount(false, 0);
        }

        /** 返回一个真实采集的非负计数。 */
        public static OptionalCount of(int value) {
            return new OptionalCount(true, value);
        }
    }

    /** 精排候选在输入、输出中的名次以及可选模型评分。 */
    record RerankedCandidateFact(
            CandidateIdentity candidate,
            int inputRank,
            int outputRank,
            OptionalScore modelScore
    ) {
        public RerankedCandidateFact {
            Objects.requireNonNull(candidate, "candidate must not be null");
            requirePositive(inputRank, "inputRank");
            requirePositive(outputRank, "outputRank");
            Objects.requireNonNull(modelScore, "modelScore must not be null");
        }
    }

    private static List<RankedCandidate> rankedCandidates(
            List<RankedCandidate> values,
            String field
    ) {
        List<RankedCandidate> immutable = immutableBounded(values, MAX_CANDIDATES, field);
        requireUniqueRanks(immutable.stream().map(RankedCandidate::rank).toList(), field + " rank");
        requireUniqueValues(
                immutable.stream().map(RankedCandidate::candidate).toList(),
                field + " candidate"
        );
        return immutable;
    }

    private static <T> List<T> immutableBounded(List<T> values, int maximum, String field) {
        List<T> immutable = List.copyOf(Objects.requireNonNull(
                values,
                field + " must not be null"
        ));
        if (immutable.size() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " entries");
        }
        return immutable;
    }

    private static void requireUniqueRanks(List<Integer> ranks, String field) {
        requireUniqueValues(ranks, field);
        int previous = 0;
        for (int rank : ranks) {
            requirePositive(rank, field);
            if (rank <= previous) {
                throw new IllegalArgumentException(field + " must be strictly ascending");
            }
            previous = rank;
        }
    }

    private static void requireUniqueValues(List<?> values, String field) {
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(field + " must not contain null values");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
    }

    private static String requireCode(String value, String field) {
        String normalized = DomainChecks.requiredText(value, field, 64)
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a stable upper-case code");
        }
        return normalized;
    }

    private static String requireIdentifier(String value, String field, int maximum) {
        String normalized = DomainChecks.requiredText(value, field, maximum);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0," + (maximum - 1) + "}")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireCount(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
