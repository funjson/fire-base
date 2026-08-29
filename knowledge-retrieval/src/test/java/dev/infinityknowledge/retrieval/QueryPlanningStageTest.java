package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.retrieval.query.ResolvedConstraints;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansion;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionRequest;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionResult;
import dev.infinityknowledge.spi.retrieval.TerminologyService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定首轮 Q0 加至多一个受控术语 Q1 的实现合同。 */
class QueryPlanningStageTest {

    /** Space 物化的资源标识和扩展词上限必须原样传给术语端口。 */
    @Test
    void passesMaterializedTerminologyConfigurationAndCreatesOneQ1() {
        AtomicReference<TerminologyExpansionRequest> captured = new AtomicReference<>();
        TerminologyService service = request -> {
            captured.set(request);
            return new TerminologyExpansionResult(
                    Optional.of(new TerminologyExpansion(
                            "订单服务 order-service ERR-1001 如何恢复",
                            List.of("order-service", "订单服务")
                    )),
                    "controlled-dictionary",
                    "engineering-v7"
            );
        };
        QueryPlanningStage stage = stage(service);
        KnowledgeQuery query = query();
        List<String> warnings = new ArrayList<>();

        var result = stage.planWithFallback(
                query,
                analyzed(query),
                ResolvedConstraints.empty(),
                new RetrievalConfiguration.FirstRound(
                        true,
                        "engineering-terms",
                        2
                ),
                4,
                new ArrayList<>(),
                warnings,
                System.nanoTime() + Duration.ofSeconds(1).toNanos()
        );

        assertEquals("engineering-terms", captured.get().terminologyResourceId());
        assertEquals(2, captured.get().maximumExpansionTerms());
        assertEquals(1, result.terminologyCallCount());
        assertEquals(2, result.plan().variants().size());
        assertEquals(QueryVariantKind.ORIGINAL, result.plan().variants().get(0).kind());
        assertEquals(
                QueryVariantKind.TERM_EXPANSION,
                result.plan().variants().get(1).kind()
        );
        assertEquals("q1", result.plan().variants().get(1).id());
        assertTrue(warnings.isEmpty());
    }

    /** 外部实现超过 Space 词数上限时不得让无界 Q1 进入 Retrieval Plan。 */
    @Test
    void fallsBackToQ0WhenTerminologyResultExceedsTermLimit() {
        TerminologyService service = ignored -> new TerminologyExpansionResult(
                Optional.of(new TerminologyExpansion(
                        "订单服务 order-service order_api 如何恢复",
                        List.of("order-service", "order_api")
                )),
                "controlled-dictionary",
                "engineering-v7"
        );
        QueryPlanningStage stage = stage(service);
        KnowledgeQuery query = query();
        List<String> warnings = new ArrayList<>();

        var result = stage.planWithFallback(
                query,
                analyzed(query),
                ResolvedConstraints.empty(),
                new RetrievalConfiguration.FirstRound(true, "engineering-terms", 1),
                2,
                new ArrayList<>(),
                warnings,
                System.nanoTime() + Duration.ofSeconds(1).toNanos()
        );

        assertEquals(1, result.terminologyCallCount());
        assertEquals(1, result.plan().variants().size());
        assertEquals(QueryVariantKind.ORIGINAL, result.plan().variants().getFirst().kind());
        assertTrue(warnings.contains("TERMINOLOGY_SERVICE_UNAVAILABLE"));
    }

    /** 关闭首轮术语增强时不应触碰任何外部术语资源。 */
    @Test
    void doesNotCallTerminologyServiceWhenSpaceDisablesExpansion() {
        AtomicInteger calls = new AtomicInteger();
        QueryPlanningStage stage = stage(request -> {
            calls.incrementAndGet();
            return TerminologyExpansionResult.none("test", "v1");
        });
        KnowledgeQuery query = query();

        var result = stage.planWithFallback(
                query,
                analyzed(query),
                ResolvedConstraints.empty(),
                new RetrievalConfiguration.FirstRound(false, "none", 0),
                1,
                new ArrayList<>(),
                new ArrayList<>(),
                System.nanoTime() + Duration.ofSeconds(1).toNanos()
        );

        assertEquals(0, calls.get());
        assertEquals(0, result.terminologyCallCount());
        assertEquals(1, result.plan().variants().size());
    }

    private QueryPlanningStage stage(TerminologyService service) {
        return new QueryPlanningStage(
                service,
                Runnable::run,
                Clock.systemUTC(),
                Duration.ofSeconds(1)
        );
    }

    private QueryPlan analyzed(KnowledgeQuery query) {
        return new QueryPlan(
                query.text(),
                query.text(),
                Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.VECTOR),
                20
        );
    }

    private KnowledgeQuery query() {
        TenantId tenantId = new TenantId("tenant-a");
        return KnowledgeQuery.online(
                UUID.randomUUID(),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("user-a"),
                        Set.of("reader"),
                        Set.of("engineering"),
                        false
                ),
                "订单服务 ERR-1001 如何恢复",
                Set.of(new KnowledgeSpaceId("engineering")),
                5,
                Map.of()
        );
    }
}
