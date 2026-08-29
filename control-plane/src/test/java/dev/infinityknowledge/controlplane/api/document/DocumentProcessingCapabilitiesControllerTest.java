package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.SpaceDocumentProcessingConfigService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证创建表单可在没有既有 Space 时读取完整能力和默认配置。 */
class DocumentProcessingCapabilitiesControllerTest {

    @Test
    void returnsDefaultConfigAndInstalledCapabilityCatalog() {
        JwtPrincipalContextFactory principalFactory = mock(
                JwtPrincipalContextFactory.class
        );
        SpaceDocumentProcessingConfigService service = mock(
                SpaceDocumentProcessingConfigService.class
        );
        Jwt jwt = mock(Jwt.class);
        var principal = SpaceDocumentProcessingConfigControllerTest.principal();
        var snapshot = SpaceDocumentProcessingConfigControllerTest
                .details(principal)
                .capabilities();
        when(principalFactory.create(jwt)).thenReturn(principal);
        when(service.capabilities(principal)).thenReturn(snapshot);
        var controller = new DocumentProcessingCapabilitiesController(
                principalFactory,
                service
        );

        DocumentProcessingCapabilitiesView view = controller.get(jwt);

        verify(service).capabilities(principal);
        assertEquals(
                "markdown-structure",
                view.defaultConfig().parserSelections().getFirst().parserId()
        );
        assertEquals("REMOVE", view.defaultConfig().cleaning().frontMatter());
        assertEquals("STRUCTURAL", view.defaultConfig().chunker().providerId());
        assertEquals(1, view.availableParsers().size());
        assertEquals(2, view.availableChunkers().size());
        assertEquals(1, view.availableTokenizers().size());
        assertEquals(0, view.availableEmbeddingProfiles().size());
    }
}
