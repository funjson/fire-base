package dev.infinityknowledge.store.elasticsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Elasticsearch 物理索引名和映射共同决定可执行目标合同。 */
class ElasticsearchPhysicalTargetContractTest {

    @Test
    void remainsStableForTheSameIndexAndMapping() {
        String first = ElasticsearchKeywordIndex.physicalTargetFingerprint(
                "knowledge_chunks_v1",
                "mapping-a"
        );
        String replay = ElasticsearchKeywordIndex.physicalTargetFingerprint(
                "knowledge_chunks_v1",
                "mapping-a"
        );

        assertEquals(first, replay);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void changesWhenIndexNameOrMappingChanges() {
        String baseline = ElasticsearchKeywordIndex.physicalTargetFingerprint(
                "knowledge_chunks_v1",
                "mapping-a"
        );

        assertNotEquals(
                baseline,
                ElasticsearchKeywordIndex.physicalTargetFingerprint(
                        "knowledge_chunks_v2",
                        "mapping-a"
                )
        );
        assertNotEquals(
                baseline,
                ElasticsearchKeywordIndex.physicalTargetFingerprint(
                        "knowledge_chunks_v1",
                        "mapping-b"
                )
        );
    }
}
