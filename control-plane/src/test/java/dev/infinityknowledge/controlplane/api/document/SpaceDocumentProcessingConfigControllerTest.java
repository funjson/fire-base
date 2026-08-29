package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.controlplane.application.ingestion.SpaceDocumentProcessingConfigService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Space 文档处理配置只读路由返回创建时固化的快照。 */
class SpaceDocumentProcessingConfigControllerTest {

    @Test
    void getExposesImmutableConfigCapabilitiesAndAuditPrincipal() {
        JwtPrincipalContextFactory principalFactory = mock(
                JwtPrincipalContextFactory.class
        );
        SpaceDocumentProcessingConfigService service = mock(
                SpaceDocumentProcessingConfigService.class
        );
        Jwt jwt = mock(Jwt.class);
        PrincipalContext principal = principal();
        when(principalFactory.create(jwt)).thenReturn(principal);
        when(service.get(principal, "engineering")).thenReturn(details(principal));
        var controller = new SpaceDocumentProcessingConfigController(
                principalFactory,
                service
        );

        SpaceDocumentProcessingConfigView view = controller.get(
                "engineering",
                jwt
        );

        verify(service).get(principal, "engineering");
        assertEquals(1L, view.version());
        assertEquals("admin-config-api", view.updatedBy());
        assertEquals("text/markdown", view.availableParsers().getFirst()
                .canonicalMediaType());
        assertEquals(2, view.availableChunkers().size());
        assertEquals(false, view.availableChunkers().get(1).available());
        assertEquals(
                "SEMANTIC_REFINEMENT_DISABLED",
                view.availableChunkers().get(1).unavailableReason()
        );
        assertNull(view.availableTokenizers().getFirst().modelProfileId());
    }

    static SpaceDocumentProcessingConfigService.ConfigDetails details(
            PrincipalContext principal
    ) {
        var config = new SpaceDocumentProcessingConfigStore
                .SpaceDocumentProcessingConfig(
                        principal.tenantId(),
                        new KnowledgeSpaceId("engineering"),
                        Map.of("text/markdown", "markdown-structure"),
                        SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                        chunker(),
                        DocumentProcessingContract.create(
                                "pipeline-v7",
                                "normalizer-schema-v2",
                                Map.of("text/markdown", "markdown-parser-v1"),
                                "cleaner-v1",
                                "chunker-v1"
                        ),
                        1L,
                        principal.principalId(),
                        Instant.parse("2026-08-16T03:00:00Z")
                );
        var capabilities = new DocumentProcessingCapabilities.Snapshot(
                List.of(new DocumentProcessingCapabilities.ParserCapability(
                        "markdown-structure",
                        "markdown-structure-v1",
                        "text/markdown",
                        List.of("text/markdown"),
                        List.of(".md"),
                        List.of("STANDARD_ELEMENTS"),
                        true
                )),
                List.of(
                        new DocumentProcessingCapabilities.ChunkerCapability(
                                "STRUCTURAL",
                                "structural-v1",
                                true,
                                "",
                                List.of("STANDARD_ELEMENTS"),
                                "{}"
                        ),
                        new DocumentProcessingCapabilities.ChunkerCapability(
                                "SEMANTIC_REFINEMENT",
                                "semantic-refinement-v1",
                                false,
                                "SEMANTIC_REFINEMENT_DISABLED",
                                List.of("STANDARD_ELEMENTS"),
                                "{}"
                        )
                ),
                List.of(new DocumentProcessingCapabilities.TokenizerCapability(
                        "UTF8_BYTE_BUDGET",
                        "utf8-byte-budget-v1",
                        "UTF-8 字节预算代理",
                        false,
                        "",
                        true,
                        ""
                )),
                List.of(),
                config.parserSelections(),
                config.chunker()
        );
        return new SpaceDocumentProcessingConfigService.ConfigDetails(
                config,
                capabilities,
                true
        );
    }

    static SpaceDocumentProcessingConfigStore.ChunkerConfiguration chunker() {
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

    static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-config-api"),
                new PrincipalId("admin-config-api"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
