package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证执行期只接受创建 Space 时已经固化的正版本配置。 */
class StoredSpaceDocumentProcessingConfigResolverTest {

    @Test
    void rejectsInactiveSpaceBeforeReadingConfiguration() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-profile");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("inactive-space");
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(false);
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(tenantId, spaceId)
        );

        verify(store, never()).find(tenantId, spaceId);
        verifyNoInteractions(capabilities);
    }

    @Test
    void rejectsActiveSpaceWithoutImmutableConfiguration() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-missing-config");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-missing-config");
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.empty());
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(tenantId, spaceId)
        );

        verifyNoInteractions(capabilities);
    }

    @Test
    void validatesAndReturnsPersistedConfiguration() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-persisted");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-persisted");
        SpaceDocumentProcessingConfig persisted = config(tenantId, spaceId, 1L);
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.of(persisted));
        when(capabilities.processingContract(persisted)).thenReturn(
                persisted.processingContract()
        );
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertSame(persisted, resolver.resolve(tenantId, spaceId));

        verify(capabilities).processingContract(persisted);
    }

    @Test
    void rejectsImplementationDriftWithStableContractError() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-drift");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-drift");
        SpaceDocumentProcessingConfig persisted = config(tenantId, spaceId, 1L);
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.of(persisted));
        when(capabilities.processingContract(persisted)).thenReturn(
                DocumentProcessingContract.create(
                        "pipeline-v8",
                        "normalizer-schema-v2",
                        Map.of("text/markdown", "markdown-parser-v1"),
                        "cleaner-v1",
                        "chunker-v1"
                )
        );
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertThrows(
                DocumentProcessingContractMismatchException.class,
                () -> resolver.resolve(tenantId, spaceId)
        );
    }

    @Test
    void translatesUnavailableAdapterIntoStableContractError() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-unavailable");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-unavailable");
        SpaceDocumentProcessingConfig persisted = config(tenantId, spaceId, 1L);
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.of(persisted));
        when(capabilities.processingContract(persisted)).thenThrow(
                new IllegalArgumentException("adapter unavailable")
        );
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertThrows(
                DocumentProcessingContractMismatchException.class,
                () -> resolver.resolve(tenantId, spaceId)
        );
    }

    @Test
    void returnsStoredConfigurationWithoutValidatingItsCurrentAdapters() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-test-override");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-test-override");
        SpaceDocumentProcessingConfig persisted = config(tenantId, spaceId, 1L);
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.of(persisted));
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertSame(persisted, resolver.resolveStored(tenantId, spaceId));

        verifyNoInteractions(capabilities);
    }

    @Test
    void rejectsVersionZeroAtExecutionTime() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-dynamic-zero");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-dynamic-zero");
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.of(
                config(tenantId, spaceId, 0L)
        ));
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(tenantId, spaceId)
        );

        verifyNoInteractions(capabilities);
    }

    @Test
    void rejectsLegacyVersionTwoAtExecutionTime() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        TenantId tenantId = new TenantId("tenant-version-two");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("space-version-two");
        SpaceDocumentProcessingConfig invalid = mock(SpaceDocumentProcessingConfig.class);
        when(invalid.tenantId()).thenReturn(tenantId);
        when(invalid.spaceId()).thenReturn(spaceId);
        when(invalid.version()).thenReturn(2L);
        when(invalid.updatedBy()).thenReturn(new PrincipalId("creator"));
        when(store.activeSpaceExists(tenantId, spaceId)).thenReturn(true);
        when(store.find(tenantId, spaceId)).thenReturn(Optional.of(invalid));
        var resolver = new StoredSpaceDocumentProcessingConfigResolver(store, capabilities);

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(tenantId, spaceId)
        );

        verifyNoInteractions(capabilities);
    }

    private static SpaceDocumentProcessingConfig config(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            long version
    ) {
        return new SpaceDocumentProcessingConfig(
                tenantId,
                spaceId,
                Map.of("text/markdown", "markdown-structure"),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                new ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        128,
                        512,
                        1_024,
                        32,
                        "{}"
                ),
                version == 1L ? contract() : null,
                version,
                new PrincipalId("creator"),
                version == 0L
                        ? Instant.EPOCH
                        : Instant.parse("2026-08-16T02:00:00Z")
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
}
