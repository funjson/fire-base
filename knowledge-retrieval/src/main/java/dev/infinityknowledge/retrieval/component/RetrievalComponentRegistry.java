package dev.infinityknowledge.retrieval.component;

import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningResult;
import dev.infinityknowledge.spi.retrieval.Reranker;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionResult;
import dev.infinityknowledge.spi.retrieval.TerminologyService;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 保存当前部署已经安装的检索组件，并把 Space 配置解析到具体执行实例。
 *
 * <p>该目录只处理当前主链需要的五类稳定组件，不提供任意插件生命周期或动态类加载。
 * Reranker 与 Coverage Judge 按配置中的 Provider/模型（Coverage 还包含 Prompt 版本）
 * 精确匹配；未知标识在任何模型调用前失败。术语服务按 Space 的资源标识选择，
 * 反馈 Planner 当前使用一个部署级已发布版本；只有 Space 真正启用相应策略时
 * 才要求它们可用。</p>
 */
public final class RetrievalComponentRegistry {
    private static final TerminologyServiceComponent DISABLED_TERMINOLOGY_SERVICE =
            new TerminologyServiceComponent(
                    new RetrievalComponentVersion(
                            "terminology-service", "runtime", "none", "v1"
                    ),
                    TerminologyService.unavailable(),
                    false
            );
    private static final FeedbackQueryPlannerComponent UNAVAILABLE_FEEDBACK_PLANNER =
            new FeedbackQueryPlannerComponent(
                    new RetrievalComponentVersion(
                            "feedback-query-planner", "runtime", "unavailable", "v1"
                    ),
                    FeedbackQueryPlanner.unavailable(),
                    false
            );
    private static final RerankerComponent RRF_ORDER_RERANKER = new RerankerComponent(
            new RetrievalComponentVersion(
                    "reranker", "runtime", "rrf-order", "v1"
            ),
            Reranker.passthrough()
    );
    private static final CoverageJudgeComponent DISABLED_COVERAGE_JUDGE =
            new CoverageJudgeComponent(
                    new RetrievalComponentVersion(
                            "coverage-judge", "runtime", "disabled", "v1"
                    ),
                    CoverageJudge.unavailable()
            );

    private final Map<String, TerminologyServiceComponent> terminologyServices;
    private final Optional<FeedbackQueryPlannerComponent> feedbackQueryPlanner;
    private final Map<ModelKey, RerankerComponent> rerankers;
    private final Map<CoverageKey, CoverageJudgeComponent> coverageJudges;
    private final Map<RetrievalChannel, Retriever> retrievers;
    private final Map<RetrievalChannel, RetrievalComponentVersion> retrieverVersions;

    /**
     * 创建仅包含已安装组件的目录。
     *
     * @param terminologyServices 可由 Space 术语资源标识选择的已发布服务
     * @param feedbackQueryPlanner 当前部署已发布的反馈 Planner
     * @param rerankers 可由 Space 选择的精排 Profile
     * @param coverageJudges 可由 Space 选择的 Coverage Profile
     * @param retrievers 当前部署按通道安装的 Retriever
     */
    public RetrievalComponentRegistry(
            List<TerminologyServiceComponent> terminologyServices,
            Optional<FeedbackQueryPlannerComponent> feedbackQueryPlanner,
            List<RerankerComponent> rerankers,
            List<CoverageJudgeComponent> coverageJudges,
            List<Retriever> retrievers
    ) {
        this.terminologyServices = indexTerminologyServices(terminologyServices);
        this.feedbackQueryPlanner = Objects.requireNonNull(
                feedbackQueryPlanner,
                "feedbackQueryPlanner must not be null"
        );
        this.rerankers = indexRerankers(rerankers);
        this.coverageJudges = indexCoverageJudges(coverageJudges);
        RetrieverIndex retrieverIndex = indexRetrievers(retrievers);
        this.retrievers = retrieverIndex.retrievers();
        this.retrieverVersions = retrieverIndex.versions();
    }

    /**
     * 把一个 Space 的最终生效配置解析成同源的执行实例与观测合同。
     *
     * @param configuration 已合并请求覆盖并通过硬上限校验的配置
     * @return 本 Space 本次访问实际使用的组件快照
     */
    public ResolvedRetrievalComponents resolve(RetrievalConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        TerminologyServiceComponent selectedTerminologyService = configuration.firstRound()
                .termExpansionEnabled()
                ? requireTerminologyService(configuration.firstRound().terminologyResourceId())
                : DISABLED_TERMINOLOGY_SERVICE;
        FeedbackQueryPlannerComponent selectedFeedbackPlanner = requiresFeedbackPlanner(
                configuration
        )
                ? feedbackQueryPlanner
                        .filter(FeedbackQueryPlannerComponent::available)
                        .orElseThrow(() -> unavailable("feedback query planner"))
                : UNAVAILABLE_FEEDBACK_PLANNER;
        RerankerComponent selectedReranker = configuration.reranker().enabled()
                ? requireReranker(configuration.reranker())
                : RRF_ORDER_RERANKER;
        CoverageJudgeComponent selectedCoverage = configuration.coverage().enabled()
                ? requireCoverageJudge(configuration.coverage())
                : DISABLED_COVERAGE_JUDGE;
        return new ResolvedRetrievalComponents(
                selectedTerminologyService,
                selectedFeedbackPlanner,
                selectedReranker,
                selectedCoverage,
                List.copyOf(retrievers.values()),
                retrieverVersions
        );
    }

    private TerminologyServiceComponent requireTerminologyService(String resourceId) {
        TerminologyServiceComponent selected = terminologyServices.get(resourceId);
        if (selected == null || !selected.available()) {
            throw unavailable("terminology resource " + resourceId);
        }
        return selected;
    }

    private RerankerComponent requireReranker(RetrievalConfiguration.Reranker requested) {
        RerankerComponent selected = rerankers.get(new ModelKey(
                requested.providerId(),
                requested.modelId()
        ));
        if (selected == null) {
            throw unavailable(
                    "reranker " + requested.providerId() + "/" + requested.modelId()
            );
        }
        return selected;
    }

    private CoverageJudgeComponent requireCoverageJudge(
            RetrievalConfiguration.Coverage requested
    ) {
        CoverageJudgeComponent selected = coverageJudges.get(new CoverageKey(
                requested.providerId(),
                requested.modelId(),
                requested.promptVersion()
        ));
        if (selected == null) {
            throw unavailable(
                    "coverage judge " + requested.providerId() + "/"
                            + requested.modelId() + "/" + requested.promptVersion()
            );
        }
        return selected;
    }

    private static boolean requiresFeedbackPlanner(RetrievalConfiguration configuration) {
        return configuration.chainNodeEnables().entrySet().stream().anyMatch(entry ->
                entry.getValue() && switch (entry.getKey()) {
                    case GAP_QUERY, PRF, STEP_BACK, HYDE -> true;
                    case RELAX_CONSTRAINTS, NARROW_CONSTRAINTS, NEXT_SPACE -> false;
                }
        );
    }

    private static Map<ModelKey, RerankerComponent> indexRerankers(
            List<RerankerComponent> values
    ) {
        Objects.requireNonNull(values, "rerankers must not be null");
        Map<ModelKey, RerankerComponent> indexed = new LinkedHashMap<>();
        for (RerankerComponent component : values) {
            Objects.requireNonNull(component, "rerankers must not contain null");
            ModelKey key = new ModelKey(
                    component.version().provider(),
                    component.version().model()
            );
            if (indexed.putIfAbsent(key, component) != null) {
                throw new IllegalArgumentException("duplicate reranker profile " + key);
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, TerminologyServiceComponent> indexTerminologyServices(
            List<TerminologyServiceComponent> values
    ) {
        Objects.requireNonNull(values, "terminologyServices must not be null");
        Map<String, TerminologyServiceComponent> indexed = new LinkedHashMap<>();
        for (TerminologyServiceComponent component : values) {
            Objects.requireNonNull(component, "terminologyServices must not contain null");
            String resourceId = component.version().model();
            if (indexed.putIfAbsent(resourceId, component) != null) {
                throw new IllegalArgumentException(
                        "duplicate terminology resource " + resourceId
                );
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<CoverageKey, CoverageJudgeComponent> indexCoverageJudges(
            List<CoverageJudgeComponent> values
    ) {
        Objects.requireNonNull(values, "coverageJudges must not be null");
        Map<CoverageKey, CoverageJudgeComponent> indexed = new LinkedHashMap<>();
        for (CoverageJudgeComponent component : values) {
            Objects.requireNonNull(component, "coverageJudges must not contain null");
            CoverageKey key = new CoverageKey(
                    component.version().provider(),
                    component.version().model(),
                    component.version().version()
            );
            if (indexed.putIfAbsent(key, component) != null) {
                throw new IllegalArgumentException("duplicate coverage profile " + key);
            }
        }
        return Map.copyOf(indexed);
    }

    private static RetrieverIndex indexRetrievers(List<Retriever> values) {
        Objects.requireNonNull(values, "retrievers must not be null");
        EnumMap<RetrievalChannel, Retriever> implementations =
                new EnumMap<>(RetrievalChannel.class);
        EnumMap<RetrievalChannel, RetrievalComponentVersion> versions =
                new EnumMap<>(RetrievalChannel.class);
        for (Retriever retriever : values) {
            Objects.requireNonNull(retriever, "retrievers must not contain null");
            RetrievalComponentVersion version = Objects.requireNonNull(
                    retriever.componentVersion(),
                    "retriever componentVersion must not be null"
            );
            String expectedRole = "retriever-" + retriever.channel().name().toLowerCase(
                    java.util.Locale.ROOT
            );
            if (!expectedRole.equals(version.component())) {
                throw new IllegalArgumentException(
                        "retriever component role does not match its channel"
                );
            }
            if (implementations.putIfAbsent(retriever.channel(), retriever) != null) {
                throw new IllegalArgumentException(
                        "duplicate retriever channel " + retriever.channel()
                );
            }
            versions.put(retriever.channel(), version);
        }
        return new RetrieverIndex(Map.copyOf(implementations), Map.copyOf(versions));
    }

    private static RetrievalComponentUnavailableException unavailable(String component) {
        return new RetrievalComponentUnavailableException(
                "effective retrieval configuration selects an unavailable " + component
        );
    }

    /** 由 Space 资源标识选择的术语服务版本和执行实例。 */
    public record TerminologyServiceComponent(
            RetrievalComponentVersion version,
            TerminologyService implementation,
            boolean available
    ) {
        /** 绑定术语资源返回的 Provider/版本，禁止 Adapter 伪造观测维度。 */
        public TerminologyServiceComponent {
            RetrievalComponentVersion installedVersion = requireRole(
                    version,
                    "terminology-service"
            );
            version = installedVersion;
            TerminologyService delegate = Objects.requireNonNull(
                    implementation,
                    "terminology service implementation must not be null"
            );
            implementation = request -> {
                TerminologyExpansionResult result = Objects.requireNonNull(
                        delegate.expand(request),
                        "terminology service must not return null"
                );
                if (!installedVersion.provider().equals(result.provider())
                        || !installedVersion.version().equals(result.resourceVersion())) {
                    throw new IllegalStateException(
                            "terminology result differs from its installed component contract"
                    );
                }
                return result;
            };
        }

        /** 创建可执行的已发布术语资源。 */
        public TerminologyServiceComponent(
                RetrievalComponentVersion version,
                TerminologyService implementation
        ) {
            this(version, implementation, true);
        }
    }

    /** 固定 Chain 反馈 Planner 的已安装版本和执行实例。 */
    public record FeedbackQueryPlannerComponent(
            RetrievalComponentVersion version,
            FeedbackQueryPlanner implementation,
            boolean available
    ) {
        /** 绑定反馈结果中的 Provider/模型，禁止动态输出伪造组件维度。 */
        public FeedbackQueryPlannerComponent {
            RetrievalComponentVersion installedVersion = requireRole(
                    version,
                    "feedback-query-planner"
            );
            version = installedVersion;
            FeedbackQueryPlanner delegate = Objects.requireNonNull(
                    implementation,
                    "feedback planner implementation must not be null"
            );
            implementation = request -> {
                FeedbackQueryPlanningResult result = Objects.requireNonNull(
                        delegate.plan(request),
                        "feedback query planner must not return null"
                );
                if (!installedVersion.provider().equals(result.provider())
                        || !installedVersion.model().equals(result.model())) {
                    throw new IllegalStateException(
                            "feedback planner result differs from its installed component contract"
                    );
                }
                return result;
            };
        }

        /** 创建可执行的已发布反馈 Planner。 */
        public FeedbackQueryPlannerComponent(
                RetrievalComponentVersion version,
                FeedbackQueryPlanner implementation
        ) {
            this(version, implementation, true);
        }
    }

    /** 可由 Space 精确选择的 Reranker Profile。 */
    public record RerankerComponent(
            RetrievalComponentVersion version,
            Reranker implementation
    ) {
        /** 校验组件角色并绑定非空实现。 */
        public RerankerComponent {
            version = requireRole(version, "reranker");
            Objects.requireNonNull(implementation, "reranker implementation must not be null");
        }
    }

    /** 可由 Space 精确选择的 Coverage Judge/Profile/Prompt 版本。 */
    public record CoverageJudgeComponent(
            RetrievalComponentVersion version,
            CoverageJudge implementation
    ) {
        /** 校验组件角色并绑定非空实现。 */
        public CoverageJudgeComponent {
            version = requireRole(version, "coverage-judge");
            Objects.requireNonNull(
                    implementation,
                    "coverage judge implementation must not be null"
            );
        }
    }

    /**
     * 一个 Space 访问期间不可变的实际执行组件快照。
     */
    public record ResolvedRetrievalComponents(
            TerminologyServiceComponent terminologyService,
            FeedbackQueryPlannerComponent feedbackQueryPlanner,
            RerankerComponent reranker,
            CoverageJudgeComponent coverageJudge,
            List<Retriever> retrievers,
            Map<RetrievalChannel, RetrievalComponentVersion> retrieverVersions
    ) {
        /** 复制集合，保证切换 Space 前同一轮不会观察到目录变化。 */
        public ResolvedRetrievalComponents {
            Objects.requireNonNull(
                    terminologyService,
                    "terminologyService must not be null"
            );
            Objects.requireNonNull(
                    feedbackQueryPlanner,
                    "feedbackQueryPlanner must not be null"
            );
            Objects.requireNonNull(reranker, "reranker must not be null");
            Objects.requireNonNull(coverageJudge, "coverageJudge must not be null");
            retrievers = List.copyOf(retrievers);
            retrieverVersions = Map.copyOf(retrieverVersions);
        }

        /** 返回一个实际安装通道的组件合同。 */
        public RetrievalComponentVersion retrieverVersion(RetrievalChannel channel) {
            RetrievalComponentVersion version = retrieverVersions.get(channel);
            if (version == null) {
                throw unavailable("retriever channel " + channel.name());
            }
            return version;
        }
    }

    private static RetrievalComponentVersion requireRole(
            RetrievalComponentVersion version,
            String expectedRole
    ) {
        Objects.requireNonNull(version, "component version must not be null");
        if (!expectedRole.equals(version.component())) {
            throw new IllegalArgumentException(
                    "component version role must be " + expectedRole
            );
        }
        return version;
    }

    private record ModelKey(String provider, String model) {
    }

    private record CoverageKey(String provider, String model, String promptVersion) {
    }

    private record RetrieverIndex(
            Map<RetrievalChannel, Retriever> retrievers,
            Map<RetrievalChannel, RetrievalComponentVersion> versions
    ) {
    }
}
