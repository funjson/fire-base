package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.controlplane.application.retrieval.SpaceRetrievalConfigurationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证检索配置控制器的稳定路径、领域映射和历史入口。 */
class SpaceRetrievalConfigurationControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void readsCurrentRevisionThroughAuthorizedApplicationService() {
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        SpaceRetrievalConfigurationService service = mock(
                SpaceRetrievalConfigurationService.class
        );
        Jwt jwt = mock(Jwt.class);
        when(principals.create(jwt)).thenReturn(principal());
        when(service.current(principal(), "engineering")).thenReturn(revision(1L));
        var controller = new SpaceRetrievalConfigurationController(principals, service);

        SpaceRetrievalConfigurationView view = controller.current("engineering", jwt);

        assertEquals("engineering", view.spaceId());
        assertEquals(1L, view.revision());
        assertEquals(
                RetrievalConfiguration.deterministicBaseline().fingerprint(),
                view.fingerprint()
        );
        verify(service).current(principal(), "engineering");
    }

    @Test
    void convertsCompletePutRequestAndReturnsNextRevision() {
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        SpaceRetrievalConfigurationService service = mock(
                SpaceRetrievalConfigurationService.class
        );
        Jwt jwt = mock(Jwt.class);
        RetrievalConfiguration configuration = RetrievalConfiguration.deterministicBaseline();
        when(principals.create(jwt)).thenReturn(principal());
        when(service.update(principal(), "engineering", 1L, configuration)).thenReturn(
                revision(2L)
        );
        var controller = new SpaceRetrievalConfigurationController(principals, service);

        SpaceRetrievalConfigurationView view = controller.update(
                "engineering",
                new UpdateSpaceRetrievalConfigurationRequest(
                        1L,
                        RetrievalConfigurationDto.from(configuration)
                ),
                jwt
        );

        assertEquals(2L, view.revision());
        verify(service).update(principal(), "engineering", 1L, configuration);
    }

    @Test
    void returnsHistoryInRevisionOrderAndKeepsHttpContract() throws Exception {
        JwtPrincipalContextFactory principals = mock(JwtPrincipalContextFactory.class);
        SpaceRetrievalConfigurationService service = mock(
                SpaceRetrievalConfigurationService.class
        );
        Jwt jwt = mock(Jwt.class);
        when(principals.create(jwt)).thenReturn(principal());
        when(service.history(principal(), "engineering", 10)).thenReturn(
                List.of(revision(2L), revision(1L))
        );
        var controller = new SpaceRetrievalConfigurationController(principals, service);

        List<SpaceRetrievalConfigurationView> history = controller.history(
                "engineering",
                10,
                jwt
        );

        assertEquals(List.of(2L, 1L), history.stream()
                .map(SpaceRetrievalConfigurationView::revision)
                .toList());
        RequestMapping mapping = SpaceRetrievalConfigurationController.class
                .getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(
                new String[]{"/api/v1/spaces/{spaceId}/retrieval-configuration"},
                mapping.value()
        );
        assertNotNull(SpaceRetrievalConfigurationController.class
                .getMethod("current", String.class, Jwt.class)
                .getAnnotation(GetMapping.class));
        assertNotNull(SpaceRetrievalConfigurationController.class
                .getMethod(
                        "update",
                        String.class,
                        UpdateSpaceRetrievalConfigurationRequest.class,
                        Jwt.class
                )
                .getAnnotation(PutMapping.class));
    }

    private static SpaceRetrievalConfiguration revision(long revision) {
        return SpaceRetrievalConfiguration.create(
                principal().tenantId(),
                new KnowledgeSpaceId("engineering"),
                revision,
                RetrievalConfiguration.deterministicBaseline(),
                principal().principalId(),
                CREATED_AT
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin-a"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
