package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.controlplane.config.retrieval.CoverageJudgeProperties;
import dev.infinityknowledge.controlplane.config.retrieval.FeedbackPlannerProperties;
import dev.infinityknowledge.controlplane.config.retrieval.SpaceRouterProperties;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.retrieval.DefaultKnowledgeGateway;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry;
import dev.infinityknowledge.retrieval.observation.HmacSha256TextFingerprinter;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingRequest;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import dev.infinityknowledge.spi.trace.TraceSink;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证生产 Bean 使用企业构造合同，不会静默落回测试用确定性目录或内存配置。
 */
class KnowledgeGatewayProductionWiringTest {

    @Test
    void injectsResolvedComponentDirectoryAndActiveIndexCatalog() {
        Ports ports = ports();
        RetrievalComponentRegistry componentRegistry = componentRegistry(ports.retriever());
        ActiveIndexGenerationCatalog indexCatalog = mock(ActiveIndexGenerationCatalog.class);
        SpaceRouter spaceRouter = mock(SpaceRouter.class);

        DefaultKnowledgeGateway gateway = assertInstanceOf(
                DefaultKnowledgeGateway.class,
                createGateway(
                        ports,
                        componentRegistry,
                        indexCatalog,
                        Optional.of(spaceRouter)
                )
        );

        assertSame(componentRegistry, field(gateway, "componentRegistry"));
        assertSame(indexCatalog, field(gateway, "activeIndexGenerationCatalog"));
        Object spaceRoutingStage = field(gateway, "spaceRoutingStage");
        assertSame(ports.catalog(), field(spaceRoutingStage, "catalog"));
        assertSame(spaceRouter, field(spaceRoutingStage, "router"));
        assertEquals(spaceRouterProperties().stageTimeout(), field(spaceRoutingStage, "timeout"));

        Object configurationStage = field(gateway, "spaceConfigurationStage");
        assertSame(ports.configurationStore(), field(configurationStage, "store"));
        assertSame(ports.configurationResolver(), field(configurationStage, "resolver"));
        assertSame(ports.hardLimits(), field(configurationStage, "hardLimits"));
        assertSame(ports.observationPublisher(), field(gateway, "observationPublisher"));
        assertEquals(feedbackPlannerProperties().stageTimeout(), field(gateway, "queryPlannerTimeout"));
        assertEquals(rerankerProperties().stageTimeout(), field(gateway, "rerankerTimeout"));
        assertEquals(coverageJudgeProperties().stageTimeout(), field(gateway, "coverageTimeout"));
    }

    @Test
    void suppliesDeterministicSpaceOrderingWhenModelRouterIsDisabled() {
        Ports ports = ports();
        RetrievalComponentRegistry componentRegistry = componentRegistry(ports.retriever());
        DefaultKnowledgeGateway gateway = assertInstanceOf(
                DefaultKnowledgeGateway.class,
                createGateway(
                        ports,
                        componentRegistry,
                        mock(ActiveIndexGenerationCatalog.class),
                        Optional.empty()
                )
        );

        SpaceRouter router = (SpaceRouter) field(
                field(gateway, "spaceRoutingStage"),
                "router"
        );
        SpaceRoutingCandidate first = new SpaceRoutingCandidate(
                new KnowledgeSpaceId("space-a"),
                "A",
                ""
        );
        SpaceRoutingCandidate second = new SpaceRoutingCandidate(
                new KnowledgeSpaceId("space-b"),
                "B",
                ""
        );
        assertEquals(
                List.of(first.spaceId(), second.spaceId()),
                router.rank(new SpaceRoutingRequest(
                        "query",
                        List.of(first, second)
                )).orderedSpaceIds()
        );
        assertSame(
                ports.retriever(),
                componentRegistry.resolve(RetrievalConfiguration.deterministicBaseline())
                        .retrievers()
                        .getFirst()
        );
    }

    private static dev.infinityknowledge.spi.KnowledgeGateway createGateway(
            Ports ports,
            RetrievalComponentRegistry componentRegistry,
            ActiveIndexGenerationCatalog indexCatalog,
            Optional<SpaceRouter> spaceRouter
    ) {
        return new KnowledgeRuntimeConfiguration().knowledgeGateway(
                ports.accessPolicy(),
                ports.activeRevisionGuard(),
                List.of(ports.retriever()),
                componentRegistry,
                indexCatalog,
                ports.catalog(),
                spaceRouter,
                ports.configurationStore(),
                ports.configurationResolver(),
                ports.hardLimits(),
                ports.observationPublisher(),
                new HmacSha256TextFingerprinter(
                        "test-only-retrieval-observation-key-0001",
                        "test-v1"
                ),
                ports.traceSink(),
                ports.executor(),
                Clock.systemUTC(),
                retrievalProperties(),
                feedbackPlannerProperties(),
                rerankerProperties(),
                spaceRouterProperties(),
                coverageJudgeProperties()
        );
    }

    private static Ports ports() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.channel()).thenReturn(RetrievalChannel.KEYWORD);
        when(retriever.componentVersion()).thenReturn(new RetrievalComponentVersion(
                "retriever-keyword",
                "test",
                "keyword",
                "v1"
        ));
        return new Ports(
                mock(AccessPolicy.class),
                mock(ActiveRevisionGuard.class),
                retriever,
                mock(RetrievalSpaceCatalog.class),
                mock(SpaceRetrievalConfigurationStore.class),
                new RetrievalConfigurationResolver(),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                mock(RetrievalObservationPublisher.class),
                mock(TraceSink.class),
                mock(ExecutorService.class)
        );
    }

    private static RetrievalComponentRegistry componentRegistry(Retriever retriever) {
        return new RetrievalComponentRegistry(
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(retriever)
        );
    }

    private static RetrievalProperties retrievalProperties() {
        return new RetrievalProperties(
                RetrievalProperties.Mode.STANDARD,
                5,
                60,
                0.5D,
                4,
                64,
                Duration.ofSeconds(35),
                Duration.ofSeconds(30)
        );
    }

    private static FeedbackPlannerProperties feedbackPlannerProperties() {
        return new FeedbackPlannerProperties(
                false,
                Duration.ofSeconds(4),
                endpoint(),
                "",
                "glm-test",
                Duration.ofSeconds(3),
                1,
                Duration.ZERO,
                24_000,
                16_384,
                512,
                "",
                0
        );
    }

    private static RerankerProperties rerankerProperties() {
        return new RerankerProperties(
                false,
                RerankerProperties.Provider.EMBEDDING,
                Duration.ofSeconds(5),
                24,
                4_096,
                4_096,
                100_000
        );
    }

    private static SpaceRouterProperties spaceRouterProperties() {
        return new SpaceRouterProperties(
                false,
                Duration.ofSeconds(3),
                endpoint(),
                "",
                "glm-test",
                Duration.ofSeconds(2),
                1,
                Duration.ZERO,
                24_000,
                8_192,
                512,
                "",
                0
        );
    }

    private static CoverageJudgeProperties coverageJudgeProperties() {
        return new CoverageJudgeProperties(
                false,
                Duration.ofSeconds(7),
                endpoint(),
                "",
                "glm-test",
                Duration.ofSeconds(6),
                1,
                Duration.ZERO,
                120_000,
                65_536,
                2_048,
                "",
                0
        );
    }

    private static URI endpoint() {
        return URI.create("https://example.invalid/chat");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing production wiring field " + name, failure);
        }
    }

    private record Ports(
            AccessPolicy accessPolicy,
            ActiveRevisionGuard activeRevisionGuard,
            Retriever retriever,
            RetrievalSpaceCatalog catalog,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationResolver configurationResolver,
            RetrievalConfigurationHardLimits hardLimits,
            RetrievalObservationPublisher observationPublisher,
            TraceSink traceSink,
            ExecutorService executor
    ) {
    }
}
