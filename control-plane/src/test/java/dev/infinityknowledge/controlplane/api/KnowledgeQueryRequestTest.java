package dev.infinityknowledge.controlplane.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固化检索过滤条件的公共 API 契约。
 */
class KnowledgeQueryRequestTest {

    @Test
    void acceptsOnlyFiltersImplementedByTheKeywordStores() {
        var request = new KnowledgeQueryRequest(
                "Redis timeout",
                null,
                null,
                Map.of("sourceType", "MARKDOWN", "language", "zh-CN")
        );

        assertEquals(2, request.filters().size());
    }

    @Test
    void rejectsUnknownOrBlankFiltersInsteadOfSilentlyBroadeningSearch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeQueryRequest(
                        "Redis timeout",
                        null,
                        null,
                        Map.of("department", "engineering")
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeQueryRequest(
                        "Redis timeout",
                        null,
                        null,
                        Map.of("language", " ")
                )
        );
    }

    @Test
    void rejectsNullCollectionMembersAsInvalidInput() {
        var spaces = new HashSet<String>();
        spaces.add(null);
        var filters = new HashMap<String, String>();
        filters.put("language", null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeQueryRequest(
                        "Redis timeout",
                        spaces,
                        null,
                        Map.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeQueryRequest(
                        "Redis timeout",
                        null,
                        null,
                        filters
                )
        );
    }
}
