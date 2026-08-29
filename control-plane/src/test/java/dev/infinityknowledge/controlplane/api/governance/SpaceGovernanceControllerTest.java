package dev.infinityknowledge.controlplane.api.governance;

import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequest;
import dev.infinityknowledge.controlplane.application.governance.KnowledgeGovernanceService;
import dev.infinityknowledge.controlplane.application.governance.TenantProvisioningService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Space 创建入口留在治理 API，并复用统一配置规范化规则。 */
class SpaceGovernanceControllerTest {

    @Test
    void createSpaceKeepsHttpContractAndPassesCanonicalConfiguration() throws Exception {
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        KnowledgeGovernanceService governance = mock(KnowledgeGovernanceService.class);
        TenantProvisioningService provisioning = mock(TenantProvisioningService.class);
        Jwt jwt = mock(Jwt.class);
        PrincipalContext principal = principal();
        when(principals.create(jwt)).thenReturn(principal);
        var controller = new SpaceGovernanceController(
                principals,
                governance,
                provisioning
        );

        controller.createSpace(request(), jwt);

        ArgumentCaptor<SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig>
                configuration = ArgumentCaptor.forClass(
                        SpaceDocumentProcessingConfigStore
                                .SpaceDocumentProcessingConfig.class
                );
        verify(provisioning).createSpace(
                eq(principal),
                eq("engineering"),
                eq("Engineering"),
                eq("Engineering knowledge space"),
                configuration.capture()
        );
        assertCanonicalConfiguration(configuration.getValue(), principal);
        assertHttpContract();
    }

    private static void assertHttpContract() throws NoSuchMethodException {
        RequestMapping controllerMapping = SpaceGovernanceController.class
                .getAnnotation(RequestMapping.class);
        assertNotNull(controllerMapping);
        assertArrayEquals(new String[]{"/api/v1/spaces"}, controllerMapping.value());

        var method = SpaceGovernanceController.class.getMethod(
                "createSpace",
                CreateSpaceRequest.class,
                Jwt.class
        );
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        assertNotNull(postMapping);
        assertEquals(0, postMapping.value().length);
        assertEquals(
                HttpStatus.NO_CONTENT,
                method.getAnnotation(ResponseStatus.class).value()
        );
    }

    private static void assertCanonicalConfiguration(
            SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig configuration,
            PrincipalContext principal
    ) {
        assertEquals(principal.tenantId(), configuration.tenantId());
        assertEquals(new KnowledgeSpaceId("engineering"), configuration.spaceId());
        assertEquals(Map.of("text/markdown", "markdown-structure"),
                configuration.parserSelections());
        assertEquals(
                SpaceDocumentProcessingConfigStore.CleaningAction.METADATA_ONLY,
                configuration.cleaning().pageNumber()
        );
        assertEquals("STRUCTURAL", configuration.chunker().providerId());
        assertEquals("UTF8_BYTE_BUDGET", configuration.chunker().tokenizerId());
        assertEquals("{}", configuration.chunker().providerConfigurationJson());
        assertEquals(0L, configuration.version());
        assertEquals(principal.principalId(), configuration.updatedBy());
        assertEquals(Instant.EPOCH, configuration.updatedAt());
    }

    private static CreateSpaceRequest request() {
        return new CreateSpaceRequest(
                "engineering",
                "Engineering",
                "Engineering knowledge space",
                new DocumentProcessingConfigRequest(
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
                )
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
