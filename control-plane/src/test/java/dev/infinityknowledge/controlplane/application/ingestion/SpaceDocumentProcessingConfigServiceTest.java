package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Space 文档处理配置只读取创建时固化的权威快照。 */
class SpaceDocumentProcessingConfigServiceTest {

    private static final TenantId TENANT_ID = new TenantId("tenant-profile");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("engineering");
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void returnsPersistedImmutableConfiguration() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        var persisted = config();
        var snapshot = snapshot();
        when(store.activeSpaceExists(TENANT_ID, SPACE_ID)).thenReturn(true);
        when(store.find(TENANT_ID, SPACE_ID)).thenReturn(Optional.of(persisted));
        when(capabilities.snapshot()).thenReturn(snapshot);
        when(capabilities.processingContract(persisted)).thenReturn(
                persisted.processingContract()
        );

        var details = service(store, capabilities).get(admin(), SPACE_ID.value());

        assertSame(persisted, details.config());
        assertSame(snapshot, details.capabilities());
        assertTrue(details.runtimeContractMatched());
    }

    @Test
    void stillReturnsFrozenContractWhenCurrentAdapterIsUnavailable() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        var persisted = config();
        when(store.activeSpaceExists(TENANT_ID, SPACE_ID)).thenReturn(true);
        when(store.find(TENANT_ID, SPACE_ID)).thenReturn(Optional.of(persisted));
        when(capabilities.snapshot()).thenReturn(snapshot());
        when(capabilities.processingContract(persisted)).thenThrow(
                new IllegalArgumentException("adapter unavailable")
        );

        var details = service(store, capabilities).get(admin(), SPACE_ID.value());

        assertSame(persisted, details.config());
        assertFalse(details.runtimeContractMatched());
    }

    @Test
    void rejectsActiveSpaceWithoutCreationConfiguration() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        when(store.activeSpaceExists(TENANT_ID, SPACE_ID)).thenReturn(true);
        when(store.find(TENANT_ID, SPACE_ID)).thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> service(store, capabilities).get(admin(), SPACE_ID.value())
        );

        verifyNoInteractions(capabilities);
    }

    @Test
    void rejectsLegacyVersionTwoInsteadOfReturningIt() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        var invalid = mock(
                SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig.class
        );
        when(invalid.version()).thenReturn(2L);
        when(invalid.updatedBy()).thenReturn(new PrincipalId("admin"));
        when(store.activeSpaceExists(TENANT_ID, SPACE_ID)).thenReturn(true);
        when(store.find(TENANT_ID, SPACE_ID)).thenReturn(Optional.of(invalid));
        when(capabilities.snapshot()).thenReturn(snapshot());

        assertThrows(
                IllegalArgumentException.class,
                () -> service(store, capabilities).get(admin(), SPACE_ID.value())
        );
    }

    @Test
    void returnsGlobalCapabilitiesWithoutExistingSpace() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        var snapshot = snapshot();
        when(capabilities.snapshot()).thenReturn(snapshot);

        assertSame(snapshot, service(store, capabilities).capabilities(admin()));

        verifyNoInteractions(store);
    }

    @Test
    void rejectsNonAdministratorBeforeReadingStore() {
        SpaceDocumentProcessingConfigStore store = mock(SpaceDocumentProcessingConfigStore.class);
        DocumentProcessingCapabilities capabilities = mock(DocumentProcessingCapabilities.class);
        var reader = new PrincipalContext(
                TENANT_ID,
                new PrincipalId("reader"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service(store, capabilities).get(reader, SPACE_ID.value())
        );

        verifyNoInteractions(store, capabilities);
    }

    private static SpaceDocumentProcessingConfigService service(
            SpaceDocumentProcessingConfigStore store,
            DocumentProcessingCapabilities capabilities
    ) {
        return new SpaceDocumentProcessingConfigService(store, capabilities);
    }

    private static DocumentProcessingCapabilities.Snapshot snapshot() {
        return new DocumentProcessingCapabilities.Snapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("text/markdown", "markdown-structure"),
                chunker()
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig config() {
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                TENANT_ID,
                SPACE_ID,
                Map.of("text/markdown", "markdown-structure"),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                chunker(),
                contract(),
                1L,
                new PrincipalId("admin"),
                NOW
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

    private static SpaceDocumentProcessingConfigStore.ChunkerConfiguration chunker() {
        return new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                "STRUCTURAL",
                "UTF8_BYTE_BUDGET",
                128,
                512,
                1_024,
                32,
                "{}"
        );
    }

    private static PrincipalContext admin() {
        return new PrincipalContext(
                TENANT_ID,
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
