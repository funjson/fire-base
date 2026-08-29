package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.retrieval.query.PlannedQuery;
import dev.infinityknowledge.retrieval.query.QueryOptimizationPlan;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 Query Optimization Plan 编译成逐 Variant 的物理 Retriever 分支。
 *
 * <p>第一版采用 {@link RetrieverAssignmentMode#RULE_ONLY}：先应用策略硬约束，再结合
 * 当前 Space 可用通道、查询形态和整轮分支预算分配。Q0 与 Q1 不再无条件复制同一组
 * 全局通道；多 Variant 首先各获得一个互补主通道，再使用剩余预算。</p>
 */
final class RetrievalPlanner {
    private static final Pattern EXACT_SIGNAL = Pattern.compile(
            "(?iu)(?:[A-Z]{2,}[-_]?[0-9]{2,}|(?:v(?:ersion)?\\s*)?"
                    + "[0-9]+(?:\\.[0-9]+){1,3}|错误码|制度编号|接口名|产品型号)"
    );
    private static final Set<RetrievalChannel> TEXT_CHANNELS = Set.of(
            RetrievalChannel.KEYWORD,
            RetrievalChannel.VECTOR
    );

    private final RetrieverAssignmentMode assignmentMode;

    /** 创建使用明确强类型模式的 Planner；当前生产只允许确定性规则模式。 */
    RetrievalPlanner(RetrieverAssignmentMode assignmentMode) {
        this.assignmentMode = Objects.requireNonNull(
                assignmentMode,
                "assignmentMode must not be null"
        );
        if (assignmentMode != RetrieverAssignmentMode.RULE_ONLY) {
            throw new IllegalArgumentException(
                    "RULE_WITH_LLM requires a bounded assignment classifier adapter"
            );
        }
    }

    /**
     * 为本轮每个 Variant 分配合法通道，并编译单通道 Retriever 请求。
     */
    List<RetrievalBranchRequest> plan(
            KnowledgeQuery original,
            QueryPlan originalPlan,
            QueryOptimizationPlan optimizationPlan,
            RetrievalConfiguration configuration,
            Set<RetrievalChannel> installedChannels,
            AccessScope scope,
            Instant deadline,
            List<String> warnings
    ) {
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(originalPlan, "originalPlan must not be null");
        Objects.requireNonNull(optimizationPlan, "optimizationPlan must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(installedChannels, "installedChannels must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        Objects.requireNonNull(warnings, "warnings must not be null");

        warnUnavailableChannels(
                originalPlan,
                optimizationPlan.variants(),
                configuration,
                installedChannels,
                warnings
        );

        List<PlannedQuery> variants = optimizationPlan.variants().stream()
                .limit(configuration.branches().maximumVariantsPerAttempt())
                .toList();
        int branchLimit = configuration.branches().maximumRetrievalBranches();
        List<Assignment> assignments = assign(
                variants,
                originalPlan,
                configuration,
                installedChannels,
                branchLimit
        );
        if (assignments.isEmpty()) {
            throw new IllegalStateException(
                    "retrieval planner produced no compatible physical branch"
            );
        }
        List<RetrievalBranchRequest> branches = new ArrayList<>(assignments.size());
        for (Assignment assignment : assignments) {
            PlannedQuery variant = assignment.variant();
            RetrievalChannel channel = assignment.channel();
            KnowledgeQuery variantQuery = new KnowledgeQuery(
                    original.requestId(),
                    original.principal(),
                    variant.text(),
                    original.spaceIds(),
                    original.topK(),
                    optimizationPlan.resolvedConstraints().appliedFilters(),
                    RetrievalConstraintInput.empty(),
                    original.retrievalTarget(),
                    original.evidenceRequirements(),
                    original.configurationOverride(),
                    original.purpose()
            );
            RetrievalConfiguration.Branch channelConfiguration =
                    configuration.branches().channels().get(channel);
            QueryPlan compiledPlan = new QueryPlan(
                    originalPlan.originalQuery(),
                    variant.text(),
                    Set.of(channel),
                    channelConfiguration.topK()
            );
            branches.add(new RetrievalBranchRequest(
                    variant.id() + ":" + channel.name().toLowerCase(Locale.ROOT),
                    variant,
                    new RetrievalRequest(variantQuery, compiledPlan, scope, deadline),
                    channelConfiguration.rrfWeight()
            ));
        }
        return List.copyOf(branches);
    }

    /** 整轮分配先保证 Variant 公平，再按规则使用剩余物理分支预算。 */
    private List<Assignment> assign(
            List<PlannedQuery> variants,
            QueryPlan originalPlan,
            RetrievalConfiguration configuration,
            Set<RetrievalChannel> installedChannels,
            int branchLimit
    ) {
        if (variants.size() == 1 && variants.getFirst().kind() == QueryVariantKind.ORIGINAL) {
            return baselineAssignments(
                    variants.getFirst(),
                    availableChannels(
                            variants.getFirst(),
                            originalPlan,
                            configuration,
                            installedChannels
                    ),
                    branchLimit
            );
        }

        List<Assignment> assignments = new ArrayList<>(branchLimit);
        EnumSet<RetrievalChannel> usedPrimaryChannels = EnumSet.noneOf(RetrievalChannel.class);
        for (PlannedQuery variant : variants) {
            if (assignments.size() >= branchLimit) {
                break;
            }
            List<RetrievalChannel> available = availableChannels(
                    variant,
                    originalPlan,
                    configuration,
                    installedChannels
            );
            RetrievalChannel primary = choosePrimary(variant, available, usedPrimaryChannels);
            assignments.add(new Assignment(variant, primary));
            usedPrimaryChannels.add(primary);
        }

        // 多 Variant 计划只把查询分析器明确选择的专业通道补给 Q0；不再把所有文本
        // 通道复制给每个 Variant。单个反馈 Variant 则按其策略软倾向补充一个文本通道。
        if (variants.size() > 1) {
            addOriginalSpecialistChannels(
                    assignments,
                    variants.getFirst(),
                    originalPlan,
                    configuration,
                    installedChannels,
                    branchLimit
            );
        } else {
            addFeedbackSecondaryChannel(
                    assignments,
                    variants.getFirst(),
                    originalPlan,
                    configuration,
                    installedChannels,
                    branchLimit
            );
        }
        return List.copyOf(assignments);
    }

    /** 单独 Q0 保留确定性 Baseline 的所有已规划通道，便于 Candidate 对比。 */
    private List<Assignment> baselineAssignments(
            PlannedQuery original,
            List<RetrievalChannel> available,
            int branchLimit
    ) {
        return available.stream()
                .limit(branchLimit)
                .map(channel -> new Assignment(original, channel))
                .toList();
    }

    private List<RetrievalChannel> availableChannels(
            PlannedQuery variant,
            QueryPlan originalPlan,
            RetrievalConfiguration configuration,
            Set<RetrievalChannel> installedChannels
    ) {
        if (variant.kind() == QueryVariantKind.HYDE) {
            return configuration.branches().channels().get(RetrievalChannel.VECTOR).enabled()
                    && installedChannels.contains(RetrievalChannel.VECTOR)
                    ? List.of(RetrievalChannel.VECTOR)
                    : List.of();
        }
        return originalPlan.channels().stream()
                .filter(channel -> configuration.branches().channels().get(channel).enabled())
                .filter(installedChannels::contains)
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
    }

    private RetrievalChannel choosePrimary(
            PlannedQuery variant,
            List<RetrievalChannel> available,
            Set<RetrievalChannel> usedPrimaryChannels
    ) {
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "query variant has no compatible retrieval channel: " + variant.kind().name()
            );
        }
        List<RetrievalChannel> preference = preferences(variant);
        for (RetrievalChannel preferred : preference) {
            if (available.contains(preferred) && !usedPrimaryChannels.contains(preferred)) {
                return preferred;
            }
        }
        for (RetrievalChannel preferred : preference) {
            if (available.contains(preferred)) {
                return preferred;
            }
        }
        return available.getFirst();
    }

    private List<RetrievalChannel> preferences(PlannedQuery variant) {
        boolean exact = EXACT_SIGNAL.matcher(variant.text()).find();
        return switch (variant.kind()) {
            case HYDE, STEP_BACK -> List.of(
                    RetrievalChannel.VECTOR,
                    RetrievalChannel.KEYWORD,
                    RetrievalChannel.PAGE,
                    RetrievalChannel.GRAPH
            );
            case TERM_EXPANSION, PRF -> List.of(
                    RetrievalChannel.KEYWORD,
                    RetrievalChannel.VECTOR,
                    RetrievalChannel.PAGE,
                    RetrievalChannel.GRAPH
            );
            case ORIGINAL, GAP_QUERY -> exact
                    ? List.of(
                            RetrievalChannel.KEYWORD,
                            RetrievalChannel.VECTOR,
                            RetrievalChannel.PAGE,
                            RetrievalChannel.GRAPH
                    )
                    : List.of(
                            RetrievalChannel.VECTOR,
                            RetrievalChannel.KEYWORD,
                            RetrievalChannel.PAGE,
                            RetrievalChannel.GRAPH
                    );
        };
    }

    private void addOriginalSpecialistChannels(
            List<Assignment> assignments,
            PlannedQuery original,
            QueryPlan originalPlan,
            RetrievalConfiguration configuration,
            Set<RetrievalChannel> installedChannels,
            int branchLimit
    ) {
        for (RetrievalChannel channel : List.of(
                RetrievalChannel.GRAPH,
                RetrievalChannel.PAGE
        )) {
            if (assignments.size() >= branchLimit) {
                return;
            }
            if (originalPlan.channels().contains(channel)
                    && configuration.branches().channels().get(channel).enabled()
                    && installedChannels.contains(channel)
                    && !contains(assignments, original, channel)) {
                assignments.add(new Assignment(original, channel));
            }
        }
    }

    private void addFeedbackSecondaryChannel(
            List<Assignment> assignments,
            PlannedQuery variant,
            QueryPlan originalPlan,
            RetrievalConfiguration configuration,
            Set<RetrievalChannel> installedChannels,
            int branchLimit
    ) {
        if (assignments.size() >= branchLimit || variant.kind() == QueryVariantKind.HYDE) {
            return;
        }
        List<RetrievalChannel> available = availableChannels(
                variant,
                originalPlan,
                configuration,
                installedChannels
        );
        long textChannelCount = available.stream().filter(TEXT_CHANNELS::contains).count();
        if (textChannelCount < 2 || !looksMixed(variant.text())) {
            return;
        }
        for (RetrievalChannel channel : preferences(variant)) {
            if (TEXT_CHANNELS.contains(channel)
                    && available.contains(channel)
                    && !contains(assignments, variant, channel)) {
                assignments.add(new Assignment(variant, channel));
                return;
            }
        }
    }

    private boolean looksMixed(String query) {
        return EXACT_SIGNAL.matcher(query).find()
                && (query.codePointCount(0, query.length()) >= 16
                        || query.contains("?")
                        || query.contains("？"));
    }

    /**
     * 分支编译只消费当前部署已安装的 Retriever；分析器选择了未安装的可选通道时，
     * 保持其余通道继续执行并输出稳定降级码，不能先生成分支再在观测阶段抛错。
     */
    private void warnUnavailableChannels(
            QueryPlan originalPlan,
            List<PlannedQuery> variants,
            RetrievalConfiguration configuration,
            Set<RetrievalChannel> installedChannels,
            List<String> warnings
    ) {
        EnumSet<RetrievalChannel> requested = EnumSet.noneOf(RetrievalChannel.class);
        originalPlan.channels().stream()
                .filter(channel -> configuration.branches().channels().get(channel).enabled())
                .forEach(requested::add);
        if (variants.stream().anyMatch(value -> value.kind() == QueryVariantKind.HYDE)
                && configuration.branches().channels().get(RetrievalChannel.VECTOR).enabled()) {
            requested.add(RetrievalChannel.VECTOR);
        }
        requested.stream()
                .filter(channel -> !installedChannels.contains(channel))
                .map(channel -> "RETRIEVER_" + channel.name() + "_UNAVAILABLE")
                .filter(warning -> !warnings.contains(warning))
                .forEach(warnings::add);
    }

    private boolean contains(
            List<Assignment> assignments,
            PlannedQuery variant,
            RetrievalChannel channel
    ) {
        return assignments.stream().anyMatch(value ->
                value.variant().id().equals(variant.id()) && value.channel() == channel
        );
    }

    /** 一个 Variant 与合法物理通道的确定性分配。 */
    private record Assignment(PlannedQuery variant, RetrievalChannel channel) {
        private Assignment {
            Objects.requireNonNull(variant, "variant must not be null");
            Objects.requireNonNull(channel, "channel must not be null");
        }
    }
}
