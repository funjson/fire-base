package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.runtime.extraction.ExtractionRunProcessor;
import dev.infinityknowledge.runtime.extraction.ExtractionRunService;
import dev.infinityknowledge.runtime.extraction.ExtractionGateEvaluator;
import dev.infinityknowledge.runtime.ingestion.DocumentPublicationService;
import dev.infinityknowledge.runtime.ingestion.IngestionRunProcessor;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.ingestion.PublishedSourceCatalog;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.store.postgres.extraction.PostgresExtractionRunStore;
import dev.infinityknowledge.store.postgres.ingestion.PostgresPublishedSourceCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/** 组装多文件测试抽取与正式摄取共用的任务、来源和 Runtime Processor。 */
@Configuration(proxyBeanMethods = false)
public class ExtractionRunRuntimeConfiguration {

    /** 注册 PostgreSQL 任务与来源原件目录。 */
    @Bean
    ExtractionRunStore extractionRunStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresExtractionRunStore(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    /** 注册来源保留、查询与取消共用的纯 Java Runtime 服务。 */
    @Bean
    ExtractionRunService extractionRunService(
            ExtractionRunStore store,
            ObjectStorage objectStorage,
            SpaceDocumentProcessingConfigResolver configResolver,
            Clock clock
    ) {
        return new ExtractionRunService(store, objectStorage, configResolver, clock);
    }

    /** 注册正式摄取的无覆盖来源目录。 */
    @Bean
    PublishedSourceCatalog publishedSourceCatalog(JdbcTemplate jdbc) {
        return new PostgresPublishedSourceCatalog(jdbc);
    }

    /** 注册所有摄取入口共用的修订发布协作者。 */
    @Bean
    DocumentPublicationService documentPublicationService(
            KnowledgeWriter writer,
            Clock clock
    ) {
        return new DocumentPublicationService(writer, clock);
    }

    /**
     * 暴露给 knowledge-jobs 的单次任务处理器。
     *
     * <p>Worker 标识只在当前进程生命周期内稳定，用于防止失联旧执行覆盖接管结果，
     * 不作为领域身份或日志标签。</p>
     */
    @Bean
    ExtractionRunProcessor extractionRunProcessor(
            ExtractionRunStore store,
            ObjectStorage objectStorage,
            ExtractionEngine extractionEngine,
            ExtractionGateEvaluator gateEvaluator,
            FileIngestionProperties fileProperties,
            ExtractionRunProperties runProperties,
            Clock clock
    ) {
        return new ExtractionRunProcessor(
                store,
                objectStorage,
                extractionEngine,
                gateEvaluator,
                fileProperties.limits(),
                "extraction-" + UUID.randomUUID(),
                runProperties.staleAfter(),
                runProperties.receivingTimeout(),
                clock
        );
    }

    /** 暴露给 knowledge-jobs 的正式多文件摄取处理器。 */
    @Bean
    IngestionRunProcessor ingestionRunProcessor(
            ExtractionRunStore store,
            PublishedSourceCatalog sourceCatalog,
            ObjectStorage objectStorage,
            ExtractionEngine extractionEngine,
            DocumentPublicationService publicationService,
            FileIngestionProperties fileProperties,
            ExtractionRunProperties runProperties,
            Clock clock
    ) {
        return new IngestionRunProcessor(
                store,
                sourceCatalog,
                objectStorage,
                extractionEngine,
                publicationService,
                fileProperties.limits(),
                "ingestion-" + UUID.randomUUID(),
                runProperties.staleAfter(),
                runProperties.receivingTimeout(),
                clock
        );
    }
}
