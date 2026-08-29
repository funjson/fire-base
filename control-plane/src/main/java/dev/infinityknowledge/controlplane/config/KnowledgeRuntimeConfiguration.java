package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.retrieval.DefaultKnowledgeGateway;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry;
import dev.infinityknowledge.retrieval.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.retrieval.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.retrieval.query.DefaultQueryAnalyzer;
import dev.infinityknowledge.controlplane.application.projection.ProjectionWorker;
import dev.infinityknowledge.controlplane.config.ingestion.ContractValidatingActiveIndexGenerationCatalog;
import dev.infinityknowledge.controlplane.config.ingestion.IndexGenerationTrackingKnowledgeWriter;
import dev.infinityknowledge.controlplane.config.ingestion.SpaceIndexingContractResolver;
import dev.infinityknowledge.controlplane.config.retrieval.FeedbackPlannerProperties;
import dev.infinityknowledge.controlplane.config.retrieval.CoverageJudgeProperties;
import dev.infinityknowledge.controlplane.config.retrieval.SpaceRouterProperties;
import dev.infinityknowledge.connector.obsidian.ObsidianSourceConnectorProvider;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.spi.KnowledgeGateway;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.audit.AuditStore;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionJobQueue;
import dev.infinityknowledge.spi.indexing.ProjectionSourceStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalTextFingerprinter;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import dev.infinityknowledge.spi.management.KnowledgeAdministrationStore;
import dev.infinityknowledge.spi.trace.TraceSink;
import dev.infinityknowledge.store.postgres.PostgresAccessPolicy;
import dev.infinityknowledge.store.postgres.PostgresActiveRevisionGuard;
import dev.infinityknowledge.store.postgres.PostgresAuditStore;
import dev.infinityknowledge.store.postgres.PostgresConnectorStateStore;
import dev.infinityknowledge.store.postgres.PostgresEvaluationStore;
import dev.infinityknowledge.store.postgres.PostgresIndexProjectionStore;
import dev.infinityknowledge.store.postgres.PostgresKnowledgeCatalog;
import dev.infinityknowledge.store.postgres.PostgresKnowledgeAdministrationStore;
import dev.infinityknowledge.store.postgres.PostgresKnowledgeGovernanceStore;
import dev.infinityknowledge.store.postgres.PostgresKnowledgeWriter;
import dev.infinityknowledge.store.postgres.PostgresKeywordRetriever;
import dev.infinityknowledge.store.postgres.PostgresProjectionJobQueue;
import dev.infinityknowledge.store.postgres.PostgresProjectionSourceStore;
import dev.infinityknowledge.store.postgres.ingestion.PostgresSpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.store.postgres.PostgresTraceSink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 组装检索 Runtime、PostgreSQL 授权策略和安全 Trace。
 */
@Configuration
public class KnowledgeRuntimeConfiguration {

    /**
     * 未启用外部关键词索引时沿用原向量物理代际，保持 PostgreSQL 默认部署合同不变。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.keyword.elasticsearch",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    IndexPhysicalContract baselineIndexPhysicalContract(
            EmbeddingProperties embeddingProperties
    ) {
        return IndexPhysicalContract.baseline(embeddingProperties.generation());
    }

    /**
     * 提供统一 UTC 时钟。
     *
     * @return UTC 时钟
     */
    @Bean
    Clock knowledgeClock() {
        return Clock.systemUTC();
    }

    /**
     * 创建固定大小的 Retriever 执行器。
     *
     * @param properties 检索配置
     * @return 执行器
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService retrievalExecutor(RetrievalProperties properties) {
        return new ThreadPoolExecutor(
                properties.parallelism(),
                properties.parallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                Thread.ofPlatform().name("knowledge-retrieval-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Isolates long-running offline evaluation work from online retrieval threads.
     */
    @Bean(name = "evaluationExecutor", destroyMethod = "shutdown")
    ExecutorService evaluationExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("knowledge-evaluation-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Isolates filesystem connector scans from online retrieval and evaluation work.
     */
    @Bean(name = "connectorExecutor", destroyMethod = "shutdown")
    ExecutorService connectorExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("knowledge-connector-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Registers the transactional connector state adapter.
     */
    @Bean
    ConnectorStateStore connectorStateStore(JdbcTemplate jdbc) {
        return new PostgresConnectorStateStore(jdbc);
    }

    /**
     * Registers the bounded Obsidian source adapter.
     */
    @Bean
    SourceConnectorProvider obsidianSourceConnectorProvider(
            ConnectorProperties properties
    ) {
        return new ObsidianSourceConnectorProvider(
                properties.normalizedAllowedRoots(),
                properties.maxFileBytes(),
                Set.of(".obsidian", ".git", ".trash", "node_modules")
        );
    }

    /**
     * 创建以 PostgreSQL ACL 为事实源的访问策略。
     *
     * @param jdbc JDBC 模板
     * @return 访问策略
     */
    @Bean
    AccessPolicy accessPolicy(NamedParameterJdbcTemplate jdbc) {
        return new PostgresAccessPolicy(jdbc);
    }

    /**
     * Uses the transactional document head to reject stale external projections and results.
     */
    @Bean
    ActiveRevisionGuard activeRevisionGuard(NamedParameterJdbcTemplate jdbc) {
        return new PostgresActiveRevisionGuard(jdbc);
    }

    /**
     * 创建事务 Trace Sink。
     *
     * @param jdbc JDBC 模板
     * @param transactionManager 事务管理器
     * @return Trace Sink
     */
    @Bean
    TraceSink traceSink(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresTraceSink(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    /**
     * 注册管理控制台只读数据端口，避免 Control Plane 直接执行 SQL。
     *
     * @param jdbc JDBC 模板
     * @return PostgreSQL 管理查询适配器
     */
    @Bean
    KnowledgeAdministrationStore knowledgeAdministrationStore(JdbcTemplate jdbc) {
        return new PostgresKnowledgeAdministrationStore(jdbc);
    }

    /** Registers durable, tenant-bound mutation audit persistence. */
    @Bean
    AuditStore auditStore(JdbcTemplate jdbc) {
        return new PostgresAuditStore(jdbc);
    }

    /**
     * 注册空间、主体和 ACL 的 PostgreSQL 治理端口。
     */
    @Bean
    KnowledgeGovernanceStore knowledgeGovernanceStore(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new PostgresKnowledgeGovernanceStore(jdbc);
    }

    /** 注册空间级 Parser 与 Chunker 配置的 PostgreSQL 端口。 */
    @Bean
    SpaceDocumentProcessingConfigStore spaceDocumentProcessingConfigStore(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new PostgresSpaceDocumentProcessingConfigStore(jdbc);
    }

    /**
     * Registers tenant-scoped evaluation persistence outside the Control Plane.
     *
     * @param jdbc JDBC template
     * @param transactionManager transaction manager
     * @return PostgreSQL evaluation store
     */
    @Bean
    EvaluationStore evaluationStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresEvaluationStore(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    /**
     * 注册无需额外中间件即可工作的 PostgreSQL 关键词召回通道。
     *
     * @param jdbc 命名参数 JDBC 模板
     * @return 关键词 Retriever
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.keyword.elasticsearch",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    Retriever postgresKeywordRetriever(NamedParameterJdbcTemplate jdbc) {
        return new PostgresKeywordRetriever(jdbc);
    }

    /**
     * 注册 PostgreSQL 修订目录。
     *
     * @param jdbc JDBC 模板
     * @return 修订目录
     */
    @Bean
    KnowledgeCatalog knowledgeCatalog(JdbcTemplate jdbc) {
        return new PostgresKnowledgeCatalog(jdbc);
    }

    /**
     * 注册 PostgreSQL 事务知识写入端口。
     *
     * @param jdbc JDBC 模板
     * @param transactionManager 事务管理器
     * @return 知识写入器
     */
    @Bean
    KnowledgeWriter knowledgeWriter(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            List<ProjectionExecutor> projectionExecutors,
            IndexProjectionStore projectionStore,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver,
            Clock clock
    ) {
        Set<ProjectionType> projections = projectionExecutors.stream()
                .map(ProjectionExecutor::projectionType)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(ProjectionType.class)
                ));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        KnowledgeWriter delegate = new PostgresKnowledgeWriter(
                jdbc,
                transaction,
                JsonMapper.builder().build(),
                projections
        );
        return new IndexGenerationTrackingKnowledgeWriter(
                delegate,
                projectionStore,
                embeddingSpec,
                physicalContract,
                indexingContractResolver,
                transaction,
                clock
        );
    }

    /**
     * Registers the transactional projection-job lease queue.
     */
    @Bean
    PostgresProjectionJobQueue projectionJobQueue(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresProjectionJobQueue(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    /**
     * Registers exact revision loading for background projectors.
     */
    @Bean
    ProjectionSourceStore projectionSourceStore(JdbcTemplate jdbc) {
        return new PostgresProjectionSourceStore(jdbc);
    }

    /**
     * Starts the generic lease-based projection worker.
     */
    @Bean
    ProjectionWorker projectionWorker(
            ProjectionJobQueue queue,
            ProjectionSourceStore sourceStore,
            ActiveRevisionGuard activeRevisionGuard,
            List<ProjectionExecutor> executors,
            IndexingProperties properties,
            Clock clock
    ) {
        return new ProjectionWorker(
                queue,
                sourceStore,
                activeRevisionGuard,
                executors,
                properties,
                clock
        );
    }

    /**
     * Registers durable index-generation and per-revision projection tracking.
     *
     * @param jdbc JDBC template
     * @param transactionManager transaction manager
     * @return projection state store
     */
    @Bean
    PostgresIndexProjectionStore indexProjectionStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresIndexProjectionStore(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    /**
     * 检索只读取与当前 Embedding、物理代际和 Space 处理合同一致的活动代际。
     */
    @Bean
    @Primary
    ActiveIndexGenerationCatalog activeIndexGenerationCatalog(
            PostgresIndexProjectionStore delegate,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver
    ) {
        return new ContractValidatingActiveIndexGenerationCatalog(
                delegate,
                embeddingSpec,
                physicalContract,
                indexingContractResolver
        );
    }

    /**
     * 组装面向 API 的 Knowledge Gateway。
     *
     * <p>Retriever 由 PostgreSQL、Elasticsearch 和 Milvus Adapter 按通道注册。
     * 某个可选通道未注册时 Runtime 会产生稳定降级警告。</p>
     *
     * @param accessPolicy 访问策略
     * @param activeRevisionGuard 活动修订二次校验
     * @param retrievers 已安装 Retriever，用于查询分析阶段确定可用通道
     * @param componentRegistry 与实际执行实例绑定的强类型检索组件目录
     * @param activeIndexGenerationCatalog Space 当前活动索引代际查询端口
     * @param spaceCatalog 已授权活动 Space 摘要目录
     * @param spaceRouter 可选模型 Space 排序器
     * @param configurationStore Space 不可变检索配置存储
     * @param configurationResolver Space 配置与请求覆盖解析器
     * @param hardLimits 部署级不可覆盖硬上限
     * @param observationPublisher 逐层检索事实发布端口
     * @param textFingerprinter 查询与变体文本的密钥化指纹端口
     * @param traceSink Trace Sink
     * @param executor Retriever 执行器
     * @param clock UTC 时钟
     * @param properties 检索配置
     * @param feedbackPlannerProperties 固定 Chain 反馈规划阶段配置
     * @param rerankerProperties 精排阶段配置
     * @param spaceRouterProperties Space 模型排序阶段配置
     * @param coverageJudgeProperties Coverage 判断阶段配置
     * @return Knowledge Gateway
     */
    @Bean
    KnowledgeGateway knowledgeGateway(
            AccessPolicy accessPolicy,
            ActiveRevisionGuard activeRevisionGuard,
            List<Retriever> retrievers,
            RetrievalComponentRegistry componentRegistry,
            ActiveIndexGenerationCatalog activeIndexGenerationCatalog,
            RetrievalSpaceCatalog spaceCatalog,
            Optional<SpaceRouter> spaceRouter,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationResolver configurationResolver,
            RetrievalConfigurationHardLimits hardLimits,
            RetrievalObservationPublisher observationPublisher,
            RetrievalTextFingerprinter textFingerprinter,
            TraceSink traceSink,
            @Qualifier("retrievalExecutor") ExecutorService executor,
            Clock clock,
            RetrievalProperties properties,
            FeedbackPlannerProperties feedbackPlannerProperties,
            RerankerProperties rerankerProperties,
            SpaceRouterProperties spaceRouterProperties,
            CoverageJudgeProperties coverageJudgeProperties
    ) {
        return new DefaultKnowledgeGateway(
                accessPolicy,
                activeRevisionGuard,
                new DefaultQueryAnalyzer(
                        properties.candidateMultiplier(),
                        retrievers.stream()
                                .map(Retriever::channel)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                ),
                componentRegistry,
                activeIndexGenerationCatalog,
                new ReciprocalRankFusion(properties.rrfConstant()),
                new DefaultEvidenceBuilder(),
                traceSink,
                spaceCatalog,
                spaceRouter.orElseGet(SpaceRouter::deterministic),
                configurationStore,
                configurationResolver,
                hardLimits,
                observationPublisher,
                textFingerprinter,
                executor,
                clock,
                properties.sufficientThreshold(),
                properties.requestTimeout(),
                spaceRouterProperties.stageTimeout(),
                feedbackPlannerProperties.stageTimeout(),
                properties.channelTimeout(),
                rerankerProperties.stageTimeout(),
                coverageJudgeProperties.stageTimeout()
        );
    }
}
