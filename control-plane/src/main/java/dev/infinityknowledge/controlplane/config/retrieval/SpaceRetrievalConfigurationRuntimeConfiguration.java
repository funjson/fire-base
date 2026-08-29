package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.store.postgres.PostgresRetrievalSpaceCatalog;
import dev.infinityknowledge.store.postgres.retrieval.PostgresSpaceRetrievalConfigurationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 组装 Space 检索配置解析、硬上限和 PostgreSQL 存储适配器。 */
@Configuration
public class SpaceRetrievalConfigurationRuntimeConfiguration {

    /** 注册只读取活动且已授权 Space 摘要的 PostgreSQL 路由目录。 */
    @Bean
    RetrievalSpaceCatalog retrievalSpaceCatalog(NamedParameterJdbcTemplate jdbc) {
        return new PostgresRetrievalSpaceCatalog(jdbc);
    }

    /** 注册不可变版本与当前指针的 PostgreSQL 存储端口。 */
    @Bean
    SpaceRetrievalConfigurationStore spaceRetrievalConfigurationStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresSpaceRetrievalConfigurationStore(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    /** 注册请求覆盖与 Space 配置共用的纯领域解析器。 */
    @Bean
    RetrievalConfigurationResolver retrievalConfigurationResolver() {
        return new RetrievalConfigurationResolver();
    }

    /**
     * 注册首轮部署的保守资源上限。
     *
     * <p>这是服务端硬边界而非 Space 默认值；新建 Space 的默认值单独物化。</p>
     */
    @Bean
    RetrievalConfigurationHardLimits retrievalConfigurationHardLimits() {
        return RetrievalConfigurationHardLimits.conservativeDefaults();
    }
}
