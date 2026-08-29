package dev.infinityknowledge.domain.retrieval.configuration;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Branch;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Branches;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.ChainNode;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Coverage;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.CrossSpace;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.FirstRound;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration.Reranker;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证检索配置的完整性、稳定指纹、强类型覆盖和系统硬上限。 */
class RetrievalConfigurationTest {

    @Test
    void deterministicBaselineFingerprintMatchesTheDatabaseBackfillContract() {
        assertEquals(
                "d1501dc9baefa3671ea42757436fafa973cb5dd4166768659c39e912f8efad98",
                RetrievalConfiguration.deterministicBaseline().fingerprint()
        );
    }

    @Test
    void fingerprintIsStableAcrossMapInsertionOrderAndChangesWithSemantics() {
        RetrievalConfiguration first = configuration(40, false);
        EnumMap<RetrievalChannel, Branch> reversed = new EnumMap<>(RetrievalChannel.class);
        reversed.put(RetrievalChannel.PAGE, new Branch(false, 10, 1.0D));
        reversed.put(RetrievalChannel.GRAPH, new Branch(false, 10, 1.0D));
        reversed.put(RetrievalChannel.VECTOR, new Branch(true, 40, 1.0D));
        reversed.put(RetrievalChannel.KEYWORD, new Branch(true, 40, 1.0D));
        RetrievalConfiguration same = new RetrievalConfiguration(
                first.firstRound(),
                new Branches(2, 4, 60, reversed),
                first.reranker(),
                first.coverage(),
                first.maximumRetrievalAttempts(),
                first.chainNodeEnables(),
                first.crossSpace()
        );

        assertEquals(first.fingerprint(), same.fingerprint());
        assertNotEquals(first.fingerprint(), configuration(41, false).fingerprint());
    }

    @Test
    void resolvesPartialOverrideWithoutMutatingSourceAndRecordsRevision() {
        RetrievalConfiguration base = configuration(40, false);
        SpaceRetrievalConfiguration source = SpaceRetrievalConfiguration.create(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                3L,
                base,
                new PrincipalId("admin-a"),
                Instant.parse("2026-08-26T00:00:00Z")
        );
        RetrievalConfigurationOverride override = new RetrievalConfigurationOverride(
                new RetrievalConfigurationOverride.FirstRoundOverride(true, null, 6),
                new RetrievalConfigurationOverride.BranchesOverride(
                        null,
                        3,
                        null,
                        Map.of(
                                RetrievalChannel.VECTOR,
                                new RetrievalConfigurationOverride.BranchOverride(
                                        null,
                                        80,
                                        1.5D
                                )
                        )
                ),
                new RetrievalConfigurationOverride.RerankerOverride(
                        null,
                        null,
                        "rerank-v2",
                        60,
                        8
                ),
                new RetrievalConfigurationOverride.CoverageOverride(
                        null,
                        null,
                        null,
                        "coverage-prompt-v2",
                        8,
                        0.85D
                ),
                4,
                Map.of(ChainNode.STEP_BACK, false),
                null
        );

        EffectiveRetrievalConfiguration effective = new RetrievalConfigurationResolver()
                .resolve(
                        source,
                        override,
                        RetrievalConfigurationHardLimits.conservativeDefaults()
                );

        assertEquals(3L, effective.sourceRevision());
        assertEquals(80, effective.configuration().branches().channels()
                .get(RetrievalChannel.VECTOR).topK());
        assertEquals(40, base.branches().channels().get(RetrievalChannel.VECTOR).topK());
        assertEquals("rerank-v2", effective.configuration().reranker().modelId());
        assertEquals(4, effective.configuration().maximumRetrievalAttempts());
        assertEquals(
                false,
                effective.configuration().chainNodeEnables().get(ChainNode.STEP_BACK)
        );
        assertEquals(true, base.chainNodeEnables().get(ChainNode.STEP_BACK));
        assertEquals(effective.configuration().fingerprint(), effective.fingerprint());
        assertNotEquals(source.fingerprint(), effective.fingerprint());
    }

    @Test
    void rejectsOverrideThatExceedsSystemHardLimitInsteadOfClipping() {
        SpaceRetrievalConfiguration source = source(configuration(40, false));
        RetrievalConfigurationResolver resolver = new RetrievalConfigurationResolver();
        RetrievalConfigurationOverride override = new RetrievalConfigurationOverride(
                null,
                new RetrievalConfigurationOverride.BranchesOverride(
                        null,
                        null,
                        null,
                        Map.of(
                                RetrievalChannel.KEYWORD,
                                new RetrievalConfigurationOverride.BranchOverride(
                                        null,
                                        201,
                                        null
                                )
                        )
                ),
                null,
                null,
                null,
                null,
                null
        );

        assertThrows(
                RetrievalConfigurationLimitExceededException.class,
                () -> resolver.resolve(
                        source,
                        override,
                        RetrievalConfigurationHardLimits.conservativeDefaults()
                )
        );
        assertThrows(
                RetrievalConfigurationLimitExceededException.class,
                () -> resolver.validate(
                        configuration(201, false),
                        RetrievalConfigurationHardLimits.conservativeDefaults()
                )
        );
    }

    @Test
    void requiresCompleteNodeSwitchesAndNextSpaceToMatchCrossSpace() {
        RetrievalConfiguration base = configuration(40, false);

        assertThrows(IllegalArgumentException.class, () -> new RetrievalConfiguration(
                base.firstRound(),
                base.branches(),
                base.reranker(),
                base.coverage(),
                base.maximumRetrievalAttempts(),
                Map.of(ChainNode.NEXT_SPACE, true, ChainNode.GAP_QUERY, true),
                new CrossSpace(true, 2)
        ));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalConfiguration(
                base.firstRound(),
                base.branches(),
                base.reranker(),
                base.coverage(),
                base.maximumRetrievalAttempts(),
                chainNodeEnables(false),
                new CrossSpace(true, 2)
        ));
    }

    @Test
    void rejectsStoredFingerprintThatDoesNotMatchCompleteConfiguration() {
        RetrievalConfiguration configuration = configuration(40, false);
        assertThrows(IllegalArgumentException.class, () -> new SpaceRetrievalConfiguration(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                1L,
                configuration,
                "0".repeat(64),
                new PrincipalId("admin-a"),
                Instant.EPOCH
        ));
    }

    static RetrievalConfiguration configuration(int topK, boolean crossSpace) {
        return new RetrievalConfiguration(
                new FirstRound(false, "engineering-terms-v1", 4),
                new Branches(
                        2,
                        4,
                        60,
                        Map.of(
                                RetrievalChannel.KEYWORD,
                                new Branch(true, topK, 1.0D),
                                RetrievalChannel.VECTOR,
                                new Branch(true, topK, 1.0D),
                                RetrievalChannel.GRAPH,
                                new Branch(false, 10, 1.0D),
                                RetrievalChannel.PAGE,
                                new Branch(false, 10, 1.0D)
                        )
                ),
                new Reranker(true, "zhipu", "rerank-v1", 40, 5),
                new Coverage(
                        true,
                        "zhipu",
                        "glm-coverage-v1",
                        "coverage-prompt-v1",
                        5,
                        0.8D
                ),
                3,
                chainNodeEnables(crossSpace),
                new CrossSpace(crossSpace, crossSpace ? 2 : 1)
        );
    }

    private static Map<ChainNode, Boolean> chainNodeEnables(boolean crossSpace) {
        return Map.of(
                ChainNode.GAP_QUERY, true,
                ChainNode.PRF, false,
                ChainNode.RELAX_CONSTRAINTS, false,
                ChainNode.NARROW_CONSTRAINTS, false,
                ChainNode.STEP_BACK, true,
                ChainNode.HYDE, false,
                ChainNode.NEXT_SPACE, crossSpace
        );
    }

    private static SpaceRetrievalConfiguration source(
            RetrievalConfiguration configuration
    ) {
        return SpaceRetrievalConfiguration.create(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                1L,
                configuration,
                new PrincipalId("admin-a"),
                Instant.EPOCH
        );
    }
}
