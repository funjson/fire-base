package dev.infinityknowledge.controlplane.application.ingestion;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 验证控制面只生成 Provider 可以稳定复核的 canonical JSON。 */
class ChunkerProviderConfigurationJsonTest {

    @Test
    void normalizesSemanticNumbersAndKeyOrder() {
        String encoded = ChunkerProviderConfigurationJson.encode(
                "semantic_refinement",
                Map.of(
                        "splitSimilarityThreshold", 0.6D,
                        "mergeSimilarityThreshold", 0.85D,
                        "embeddingProfileId", "test/embedding@3",
                        "contextSlices", 1L
                )
        );

        assertThat(encoded).isEqualTo(
                "{\"contextSlices\":1,\"embeddingProfileId\":\"test/embedding@3\","
                        + "\"mergeSimilarityThreshold\":0.85,"
                        + "\"splitSimilarityThreshold\":0.6}"
        );
        assertThat(ChunkerProviderConfigurationJson.decode(encoded))
                .containsEntry("contextSlices", 1)
                .containsEntry("embeddingProfileId", "test/embedding@3");
    }

    @Test
    void rejectsUnsupportedOrNonCanonicalBuiltInFields() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ChunkerProviderConfigurationJson.encode(
                        "STRUCTURAL",
                        Map.of("unsupported", true)
                )
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ChunkerProviderConfigurationJson.encode(
                        "SEMANTIC_REFINEMENT",
                        Map.of(
                                "unsupported", 1,
                                "embeddingProfileId", "test/embedding@3",
                                "mergeSimilarityThreshold", 0.85D,
                                "splitSimilarityThreshold", 0.6D
                        )
                )
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ChunkerProviderConfigurationJson.encode(
                        "SEMANTIC_REFINEMENT",
                        Map.of(
                                "contextSlices", 1,
                                "embeddingProfileId", " test/embedding@3 ",
                                "mergeSimilarityThreshold", 0.85D,
                                "splitSimilarityThreshold", 0.6D
                        )
                )
        );
    }
}
