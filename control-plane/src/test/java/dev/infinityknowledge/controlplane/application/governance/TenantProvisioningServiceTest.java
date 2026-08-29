package dev.infinityknowledge.controlplane.application.governance;

import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Space 治理资源与不可变处理配置的创建编排。 */
class TenantProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("engineering");
    private static final String DESCRIPTION = "Engineering knowledge space";

    @Test
    void createsGovernanceThenValidatesBeforeWritingImmutableConfiguration() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        SpaceRetrievalConfigurationStore retrievalConfigs = mock(
                SpaceRetrievalConfigurationStore.class
        );
        var requested = requestedConfig(512);
        var frozen = requested.withProcessingContract(contract());
        when(governance.createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        ))
                .thenReturn(new KnowledgeGovernanceStore.CreateSpaceResult(
                        true,
                        "Engineering",
                        DESCRIPTION,
                        "ACTIVE"
                ));
        when(capabilities.processingContract(requested)).thenReturn(contract());
        when(configs.createImmutable(frozen)).thenReturn(
                SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED
        );
        when(retrievalConfigs.appendAndActivate(any(), eq(0L))).thenReturn(
                SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED
        );

        service(governance, configs, retrievalConfigs, capabilities).createSpace(
                admin(),
                SPACE_ID.value(),
                "Engineering",
                DESCRIPTION,
                requested
        );

        InOrder order = inOrder(capabilities, governance, configs, retrievalConfigs);
        order.verify(governance).createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        );
        order.verify(capabilities).validateForCreation(
                requested.parserSelections(),
                requested.chunker()
        );
        order.verify(capabilities).processingContract(requested);
        order.verify(configs).createImmutable(frozen);
        order.verify(retrievalConfigs).appendAndActivate(
                org.mockito.ArgumentMatchers.argThat(value ->
                        value.tenantId().equals(admin().tenantId())
                                && value.spaceId().equals(SPACE_ID)
                                && value.revision() == 1L
                                && value.configuration().equals(
                                dev.infinityknowledge.domain.retrieval.configuration
                                        .RetrievalConfiguration.deterministicBaseline()
                        )
                ),
                eq(0L)
        );
    }

    @Test
    void repeatedSameNameAndConfigurationIsIdempotent() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var requested = requestedConfig(512);
        when(governance.createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        ))
                .thenReturn(new KnowledgeGovernanceStore.CreateSpaceResult(
                        false,
                        "Engineering",
                        DESCRIPTION,
                        "ACTIVE"
                ));
        when(configs.find(admin().tenantId(), SPACE_ID)).thenReturn(Optional.of(
                persistedConfig(512)
        ));

        assertDoesNotThrow(() -> service(governance, configs, capabilities).createSpace(
                admin(),
                SPACE_ID.value(),
                "Engineering",
                DESCRIPTION,
                requested
        ));

        verify(configs, never()).createImmutable(requested);
        verifyNoInteractions(capabilities);
    }

    @Test
    void repeatedDifferentConfigurationReturnsStableConflict() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var requested = requestedConfig(768);
        when(governance.createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        ))
                .thenReturn(new KnowledgeGovernanceStore.CreateSpaceResult(
                        false,
                        "Engineering",
                        DESCRIPTION,
                        "ACTIVE"
                ));
        when(configs.find(admin().tenantId(), SPACE_ID)).thenReturn(Optional.of(
                persistedConfig(512)
        ));

        KnowledgeSpaceConflictException conflict = assertThrows(
                KnowledgeSpaceConflictException.class,
                () -> service(governance, configs, capabilities).createSpace(
                        admin(),
                        SPACE_ID.value(),
                        "Engineering",
                        DESCRIPTION,
                        requested
                )
        );

        assertEquals(
                "SPACE_ALREADY_EXISTS_WITH_DIFFERENT_CONFIGURATION",
                conflict.code()
        );
        verify(configs, never()).createImmutable(requested);
        verifyNoInteractions(capabilities);
    }

    @Test
    void repeatedDifferentNameOrInactiveSpaceReturnsConflictWithoutConfigWrite() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var requested = requestedConfig(512);
        when(governance.createSpace(
                admin(), SPACE_ID, "New name", DESCRIPTION, NOW
        ))
                .thenReturn(new KnowledgeGovernanceStore.CreateSpaceResult(
                        false,
                        "Engineering",
                        DESCRIPTION,
                        "ARCHIVED"
                ));

        assertThrows(
                KnowledgeSpaceConflictException.class,
                () -> service(governance, configs, capabilities).createSpace(
                        admin(),
                        SPACE_ID.value(),
                        "New name",
                        DESCRIPTION,
                        requested
                )
        );

        verifyNoInteractions(configs);
        verifyNoInteractions(capabilities);
    }

    @Test
    void capabilityFailureRollsBackGovernanceCreationWithoutWritingConfiguration() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var requested = requestedConfig(512);
        when(governance.createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        ))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager
                            .isActualTransactionActive());
                    return new KnowledgeGovernanceStore.CreateSpaceResult(
                            true,
                            "Engineering",
                            DESCRIPTION,
                            "ACTIVE"
                    );
                });
        org.mockito.Mockito.doThrow(new IllegalArgumentException("unavailable"))
                .when(capabilities)
                .validateForCreation(
                        requested.parserSelections(),
                        requested.chunker()
                );
        var transactionManager = new RecordingTransactionManager();

        assertThrows(
                IllegalArgumentException.class,
                () -> transactional(
                        service(governance, configs, capabilities),
                        transactionManager
                ).createSpace(
                        admin(),
                        SPACE_ID.value(),
                        "Engineering",
                        DESCRIPTION,
                        requested
                )
        );

        verify(governance).createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        );
        verifyNoInteractions(configs);
        assertEquals(0, transactionManager.commits);
        assertEquals(1, transactionManager.rollbacks);
    }

    @Test
    void configurationWriteFailureRollsBackTheSharedCreationTransaction() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var requested = requestedConfig(512);
        var frozen = requested.withProcessingContract(contract());
        when(governance.createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        ))
                .thenReturn(new KnowledgeGovernanceStore.CreateSpaceResult(
                        true,
                        "Engineering",
                        DESCRIPTION,
                        "ACTIVE"
                ));
        when(capabilities.processingContract(requested)).thenReturn(contract());
        when(configs.createImmutable(frozen)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("config write failed");
        });
        var transactionManager = new RecordingTransactionManager();
        TenantProvisioningService transactional = transactional(
                service(governance, configs, capabilities),
                transactionManager
        );

        assertThrows(
                IllegalStateException.class,
                () -> transactional.createSpace(
                        admin(),
                        SPACE_ID.value(),
                        "Engineering",
                        DESCRIPTION,
                        requested
                )
        );

        assertEquals(0, transactionManager.commits);
        assertEquals(1, transactionManager.rollbacks);
    }

    @Test
    void retrievalConfigurationFailureRollsBackTheSharedCreationTransaction() {
        KnowledgeGovernanceStore governance = mock(KnowledgeGovernanceStore.class);
        SpaceDocumentProcessingConfigStore configs = mock(
                SpaceDocumentProcessingConfigStore.class
        );
        SpaceRetrievalConfigurationStore retrievalConfigs = mock(
                SpaceRetrievalConfigurationStore.class
        );
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var requested = requestedConfig(512);
        var frozen = requested.withProcessingContract(contract());
        when(governance.createSpace(
                admin(), SPACE_ID, "Engineering", DESCRIPTION, NOW
        ))
                .thenReturn(new KnowledgeGovernanceStore.CreateSpaceResult(
                        true,
                        "Engineering",
                        DESCRIPTION,
                        "ACTIVE"
                ));
        when(capabilities.processingContract(requested)).thenReturn(contract());
        when(configs.createImmutable(frozen)).thenReturn(
                SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED
        );
        when(retrievalConfigs.appendAndActivate(any(), eq(0L))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("retrieval config write failed");
        });
        var transactionManager = new RecordingTransactionManager();
        TenantProvisioningService transactional = transactional(
                service(governance, configs, retrievalConfigs, capabilities),
                transactionManager
        );

        assertThrows(
                IllegalStateException.class,
                () -> transactional.createSpace(
                        admin(),
                        SPACE_ID.value(),
                        "Engineering",
                        DESCRIPTION,
                        requested
                )
        );

        assertEquals(0, transactionManager.commits);
        assertEquals(1, transactionManager.rollbacks);
    }

    private static TenantProvisioningService transactional(
            TenantProvisioningService target,
            RecordingTransactionManager transactionManager
    ) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource()
        );
        proxyFactory.addAdvice(interceptor);
        return (TenantProvisioningService) proxyFactory.getProxy();
    }

    private static TenantProvisioningService service(
            KnowledgeGovernanceStore governance,
            SpaceDocumentProcessingConfigStore configs,
            DocumentProcessingCapabilities capabilities
    ) {
        SpaceRetrievalConfigurationStore retrievalConfigs = mock(
                SpaceRetrievalConfigurationStore.class
        );
        when(retrievalConfigs.appendAndActivate(any(), eq(0L))).thenReturn(
                SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED
        );
        return service(governance, configs, retrievalConfigs, capabilities);
    }

    private static TenantProvisioningService service(
            KnowledgeGovernanceStore governance,
            SpaceDocumentProcessingConfigStore configs,
            SpaceRetrievalConfigurationStore retrievalConfigs,
            DocumentProcessingCapabilities capabilities
    ) {
        return new TenantProvisioningService(
                governance,
                configs,
                retrievalConfigs,
                capabilities,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            requestedConfig(int targetTokens) {
        return config(targetTokens, 0L, Instant.EPOCH);
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            persistedConfig(int targetTokens) {
        return config(targetTokens, 1L, NOW);
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig config(
            int targetTokens,
            long version,
            Instant updatedAt
    ) {
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                admin().tenantId(),
                SPACE_ID,
                Map.of("text/markdown", "markdown-structure"),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        128,
                        targetTokens,
                        1_024,
                        32,
                        "{}"
                ),
                version == 1L ? contract() : null,
                version,
                admin().principalId(),
                updatedAt
        );
    }

    private static DocumentProcessingContract contract() {
        return DocumentProcessingContract.create(
                "pipeline-v7",
                "normalizer-schema-v2",
                Map.of("text/markdown", "markdown-parser-v1"),
                "cleaner-v1",
                "chunker-v1"
        );
    }

    private static PrincipalContext admin() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin-a"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }

    /** 记录 Spring 声明式事务完成方式，不引入外部数据库即可验证回滚边界。 */
    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // AbstractPlatformTransactionManager 会据此激活本线程的事务同步状态。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
