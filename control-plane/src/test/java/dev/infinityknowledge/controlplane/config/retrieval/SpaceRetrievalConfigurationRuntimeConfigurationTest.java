package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.store.postgres.retrieval.PostgresSpaceRetrievalConfigurationStore;
import dev.infinityknowledge.store.postgres.PostgresRetrievalSpaceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** 验证检索配置端口、解析器和服务端硬上限拥有明确 Spring 装配。 */
class SpaceRetrievalConfigurationRuntimeConfigurationTest {

    @Test
    void wiresPostgresStoreAndSharedDomainValidationComponents() {
        var configuration = new SpaceRetrievalConfigurationRuntimeConfiguration();
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(
                PlatformTransactionManager.class
        );

        assertInstanceOf(
                PostgresSpaceRetrievalConfigurationStore.class,
                configuration.spaceRetrievalConfigurationStore(jdbc, transactionManager)
        );
        assertInstanceOf(
                PostgresRetrievalSpaceCatalog.class,
                configuration.retrievalSpaceCatalog(jdbc)
        );
        assertInstanceOf(
                RetrievalConfigurationResolver.class,
                configuration.retrievalConfigurationResolver()
        );
        RetrievalConfigurationHardLimits limits =
                configuration.retrievalConfigurationHardLimits();
        assertEquals(
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                limits
        );
    }
}
