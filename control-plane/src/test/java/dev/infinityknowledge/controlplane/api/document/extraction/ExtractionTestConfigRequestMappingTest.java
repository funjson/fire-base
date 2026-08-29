package dev.infinityknowledge.controlplane.api.document.extraction;

import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequest;
import dev.infinityknowledge.controlplane.application.ingestion.extraction.ExtractionRunApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.runtime.extraction.ExtractionConfigSnapshots;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证上传和 Golden Dataset 入口复用同一套 TEST_ONLY 配置规范化规则。 */
class ExtractionTestConfigRequestMappingTest {

    private static final Instant NOW = Instant.parse("2026-08-22T06:00:00Z");

    @Test
    void multipartEntryMapsTestConfigBeforeCallingApplicationService() {
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        ExtractionRunApplicationService service = mock(ExtractionRunApplicationService.class);
        Jwt jwt = mock(Jwt.class);
        when(principals.create(jwt)).thenReturn(principal());
        when(service.create(
                any(), anyString(), any(), anyString(), any(), any(), any()
        )).thenReturn(run());
        var controller = new ExtractionRunController(principals, service);

        controller.create(
                "engineering",
                new MockMultipartFile[]{new MockMultipartFile(
                        "files",
                        "runbook.md",
                        "text/markdown",
                        "# Runbook".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )},
                "zh-CN",
                null,
                null,
                testConfigRequest(),
                jwt
        );

        ArgumentCaptor<SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig>
                testConfig = ArgumentCaptor.forClass(
                        SpaceDocumentProcessingConfigStore
                                .SpaceDocumentProcessingConfig.class
                );
        verify(service).create(
                eq(principal()),
                eq("engineering"),
                any(),
                eq("zh-CN"),
                eq(null),
                eq(null),
                testConfig.capture()
        );
        assertCanonicalTestConfig(testConfig.getValue());
    }

    @Test
    void datasetEntryMapsTheSameTestConfigBeforeCallingApplicationService() {
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        ExtractionRunApplicationService service = mock(ExtractionRunApplicationService.class);
        Jwt jwt = mock(Jwt.class);
        when(principals.create(jwt)).thenReturn(principal());
        when(service.createFromDataset(
                any(), anyString(), anyString(), anyString(), any(), any()
        )).thenReturn(run());
        var controller = new ExtractionDatasetController(principals, service);

        controller.run(
                "engineering",
                "extraction-core-v1",
                new ExtractionDatasetController.DatasetRunRequest(
                        "zh-CN",
                        null,
                        testConfigRequest()
                ),
                jwt
        );

        ArgumentCaptor<SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig>
                testConfig = ArgumentCaptor.forClass(
                        SpaceDocumentProcessingConfigStore
                                .SpaceDocumentProcessingConfig.class
                );
        verify(service).createFromDataset(
                eq(principal()),
                eq("engineering"),
                eq("extraction-core-v1"),
                eq("zh-CN"),
                eq(null),
                testConfig.capture()
        );
        assertCanonicalTestConfig(testConfig.getValue());
    }

    private static void assertCanonicalTestConfig(
            SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig testConfig
    ) {
        assertEquals(principal().tenantId(), testConfig.tenantId());
        assertEquals(new KnowledgeSpaceId("engineering"), testConfig.spaceId());
        assertEquals(0L, testConfig.version());
        assertEquals(
                Map.of("text/markdown", "markdown-structure"),
                testConfig.parserSelections()
        );
        assertEquals("STRUCTURAL", testConfig.chunker().providerId());
        assertEquals("UTF8_BYTE_BUDGET", testConfig.chunker().tokenizerId());
        assertEquals("{}", testConfig.chunker().providerConfigurationJson());
    }

    private static DocumentProcessingConfigRequest testConfigRequest() {
        return new DocumentProcessingConfigRequest(
                List.of(new DocumentProcessingConfigRequest.ParserSelection(
                        " TEXT/MARKDOWN ",
                        " markdown-structure "
                )),
                new DocumentProcessingConfigRequest.CleaningConfiguration(
                        "keep",
                        "remove",
                        "metadata_only",
                        "keep",
                        "remove"
                ),
                new DocumentProcessingConfigRequest.ChunkerConfiguration(
                        " structural ",
                        " UTF8_BYTE_BUDGET ",
                        64,
                        128,
                        256,
                        16,
                        Map.of()
                )
        );
    }

    private static ExtractionRunStore.RunSnapshot run() {
        var config = activeConfig();
        return new ExtractionRunStore.RunSnapshot(
                UUID.randomUUID(),
                config.tenantId(),
                config.spaceId(),
                ExtractionMode.TEST_ONLY,
                ExtractionRunStore.RunStatus.QUEUED,
                "zh-CN",
                config.version(),
                ExtractionConfigSnapshots.capture(
                        config,
                        config.processingContract(),
                        DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
                ),
                null,
                null,
                ExtractionGateStatus.NOT_EVALUATED,
                null,
                principal().principalId(),
                null,
                NOW,
                NOW,
                null,
                null,
                null,
                List.of()
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            activeConfig() {
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                principal().tenantId(),
                new KnowledgeSpaceId("engineering"),
                Map.of("text/markdown", "markdown-structure"),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        128,
                        256,
                        16,
                        "{}"
                ),
                contract(),
                1L,
                principal().principalId(),
                NOW
        );
    }

    private static DocumentProcessingContract contract() {
        return DocumentProcessingContract.create(
                DocumentProcessingContractFactory.PIPELINE_CONTRACT,
                DocumentProcessingContractFactory.NORMALIZER_SCHEMA_CONTRACT,
                Map.of("text/markdown", "markdown-structure/v1"),
                "cleaner/v1",
                "structural/utf8-byte-budget/v1"
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
