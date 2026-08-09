package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.runtime.DefaultKnowledgeGateway;
import dev.infinityknowledge.runtime.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.runtime.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.runtime.query.DefaultQueryAnalyzer;
import dev.infinityknowledge.controlplane.application.ProjectionWorker;
import dev.infinityknowledge.connector.obsidian.ObsidianSourceConnectorProvider;
import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.MarkdownElementParser;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.spi.KnowledgeGateway;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionJobQueue;
import dev.infinityknowledge.spi.indexing.ProjectionSourceStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import dev.infinityknowledge.spi.management.KnowledgeAdministrationStore;
import dev.infinityknowledge.spi.trace.TraceSink;
import dev.infinityknowledge.store.postgres.PostgresAccessPolicy;
import dev.infinityknowledge.store.postgres.PostgresActiveRevisionGuard;
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
import dev.infinityknowledge.store.postgres.PostgresTraceSink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 注册无状态 Markdown 结构解析器。
     *
     * @return Markdown 解析器
     */
    @Bean
    MarkdownElementParser markdownElementParser() {
        return new MarkdownElementParser();
    }

    /**
     * 按运行配置创建标题感知切分器。
     *
     * @param properties 摄取配置
     * @return 切分器
     */
    @Bean
    HeadingAwareChunker headingAwareChunker(IngestionProperties properties) {
        return new HeadingAwareChunker(
                properties.targetChunkCharacters(),
                properties.maximumChunkCharacters()
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
        return new PostgresTraceSink(jdbc, new TransactionTemplate(transactionManager));
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

    /**
     * 注册空间、主体和 ACL 的 PostgreSQL 治理端口。
     */
    @Bean
    KnowledgeGovernanceStore knowledgeGovernanceStore(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new PostgresKnowledgeGovernanceStore(jdbc);
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
            VectorProperties vectorProperties,
            ElasticsearchProperties elasticsearchProperties
    ) {
        Set<ProjectionType> projections = EnumSet.noneOf(ProjectionType.class);
        if (vectorProperties.enabled()) {
            projections.add(ProjectionType.VECTOR);
        }
        if (elasticsearchProperties.enabled()) {
            projections.add(ProjectionType.KEYWORD);
        }
        return new PostgresKnowledgeWriter(
                jdbc,
                new TransactionTemplate(transactionManager),
                JsonMapper.builder().build(),
                projections
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
    IndexProjectionStore indexProjectionStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresIndexProjectionStore(
                jdbc,
                new TransactionTemplate(transactionManager)
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
     * @param retrievers 已安装 Retriever
     * @param traceSink Trace Sink
     * @param executor Retriever 执行器
     * @param clock UTC 时钟
     * @param properties 检索配置
     * @return Knowledge Gateway
     */
    @Bean
    KnowledgeGateway knowledgeGateway(
            AccessPolicy accessPolicy,
            ActiveRevisionGuard activeRevisionGuard,
            List<Retriever> retrievers,
            TraceSink traceSink,
            @Qualifier("retrievalExecutor") ExecutorService executor,
            Clock clock,
            RetrievalProperties properties
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
                retrievers,
                new ReciprocalRankFusion(properties.rrfConstant()),
                new DefaultEvidenceBuilder(),
                traceSink,
                executor,
                clock,
                properties.sufficientThreshold()
        );
    }
}
