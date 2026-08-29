package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证投影与检索共用的索引代际合同指纹保持稳定且能识别漂移。 */
class IndexGenerationIdentityTest {

    private static final EmbeddingSpec SPEC = new EmbeddingSpec(
            "zhipu",
            "embedding-3",
            2_048
    );

    @Test
    void producesStableLowercaseSha256() {
        String first = IndexGenerationIdentity.configurationVersion(
                SPEC,
                IndexPhysicalContract.baseline("v2"),
                "normalizer-v1",
                "chunker-v1"
        );
        String replay = IndexGenerationIdentity.configurationVersion(
                SPEC,
                IndexPhysicalContract.baseline("v2"),
                "normalizer-v1",
                "chunker-v1"
        );

        assertEquals(first, replay);
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(legacyBaselineIdentity(), first);
    }

    @Test
    void changesWhenPhysicalGenerationChanges() {
        String first = IndexGenerationIdentity.configurationVersion(
                SPEC,
                IndexPhysicalContract.baseline("v2"),
                "normalizer-v1",
                "chunker-v1"
        );
        String changed = IndexGenerationIdentity.configurationVersion(
                SPEC,
                IndexPhysicalContract.baseline("v3"),
                "normalizer-v1",
                "chunker-v1"
        );

        assertNotEquals(first, changed);
    }

    @Test
    void changesWhenKeywordPhysicalTargetChanges() {
        String first = IndexGenerationIdentity.configurationVersion(
                SPEC,
                IndexPhysicalContract.withKeywordTarget("v2", "a".repeat(64)),
                "normalizer-v1",
                "chunker-v1"
        );
        String changed = IndexGenerationIdentity.configurationVersion(
                SPEC,
                IndexPhysicalContract.withKeywordTarget("v2", "b".repeat(64)),
                "normalizer-v1",
                "chunker-v1"
        );

        assertNotEquals(first, changed);
    }

    private static String legacyBaselineIdentity() {
        String value = String.join(
                "\u001F",
                SPEC.providerId(),
                SPEC.modelId(),
                Integer.toString(SPEC.dimensions()),
                "v2",
                "normalizer-v1",
                "chunker-v1"
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
