package dev.infinityknowledge.spi.access;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessScopeTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");

    @Test
    void representsAllDocumentsInsideExplicitSpaces() {
        AccessScope scope = AccessScope.all(TENANT, Set.of(SPACE));

        assertEquals(AccessScope.Mode.ALL, scope.mode());
        assertTrue(scope.allowsSpace(SPACE));
        assertTrue(scope.allowsDocument("any-document"));
        assertFalse(scope.deniesAll());
    }

    @Test
    void representsDocumentWhitelistWithoutEmptySetConvention() {
        AccessScope scope = AccessScope.only(
                TENANT,
                Set.of(SPACE),
                Set.of("document-1")
        );

        assertEquals(AccessScope.Mode.ONLY, scope.mode());
        assertTrue(scope.restrictsDocuments());
        assertTrue(scope.allowsDocument("document-1"));
        assertFalse(scope.allowsDocument("document-2"));
    }

    @Test
    void representsExplicitDenial() {
        AccessScope scope = AccessScope.denyAll(TENANT);

        assertEquals(AccessScope.Mode.DENY_ALL, scope.mode());
        assertTrue(scope.deniesAll());
        assertFalse(scope.allowsSpace(SPACE));
        assertFalse(scope.allowsDocument("document-1"));
    }

    @Test
    void rejectsAmbiguousOrContradictoryModes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessScope(TENANT, AccessScope.Mode.ALL, Set.of(), Set.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessScope(
                        TENANT,
                        AccessScope.Mode.ONLY,
                        Set.of(SPACE),
                        Set.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessScope(
                        TENANT,
                        AccessScope.Mode.DENY_ALL,
                        Set.of(SPACE),
                        Set.of()
                )
        );
    }

    @Test
    void preservesLegacyConstructorSemanticsExplicitly() {
        assertEquals(
                AccessScope.Mode.ALL,
                new AccessScope(TENANT, Set.of(SPACE), Set.of()).mode()
        );
        assertEquals(
                AccessScope.Mode.ONLY,
                new AccessScope(TENANT, Set.of(SPACE), Set.of("document-1")).mode()
        );
    }
}
