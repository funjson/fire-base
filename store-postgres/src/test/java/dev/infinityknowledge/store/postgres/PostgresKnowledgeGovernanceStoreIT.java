package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用真实 PostgreSQL 验证主体登记、ACL 和租户隔离。
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresKnowledgeGovernanceStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static JdbcTemplate jdbc;
    private static PostgresKnowledgeGovernanceStore store;

    @BeforeAll
    static void migrate() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresKnowledgeGovernanceStore(
                new NamedParameterJdbcTemplate(dataSource)
        );
    }

    @Test
    void repeatedCreationReportsOriginalDefinitionWithoutMutatingGovernanceRows() {
        PrincipalContext admin = admin("tenant-governance-repeat", "admin-repeat");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        Instant now = Instant.parse("2026-08-03T00:00:00Z");

        var created = store.createSpace(
                admin,
                spaceId,
                "Engineering",
                "Engineering knowledge space",
                now
        );
        var repeated = store.createSpace(
                admin,
                spaceId,
                "Renamed",
                "Renamed knowledge space",
                now.plusSeconds(30)
        );

        assertTrue(created.created());
        assertFalse(repeated.created());
        assertEquals("Engineering", repeated.name());
        assertEquals("Engineering knowledge space", repeated.description());
        assertEquals("ACTIVE", repeated.status());
        assertEquals("Engineering", jdbc.queryForObject("""
                SELECT name
                  FROM knowledge_space
                 WHERE tenant_id = ?
                   AND id = ?
                """, String.class, admin.tenantId().value(), spaceId.value()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*)
                  FROM knowledge_space_acl
                 WHERE tenant_id = ?
                   AND space_id = ?
                """, Integer.class, admin.tenantId().value(), spaceId.value()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*)
                  FROM connector_instance
                 WHERE tenant_id = ?
                   AND space_id = ?
                """, Integer.class, admin.tenantId().value(), spaceId.value()));
    }

    @Test
    void registersPrincipalAndAppliesRoleGrantWithoutCrossTenantLeakage() {
        PrincipalContext adminA = admin("tenant-governance-a", "admin-a");
        PrincipalContext readerA = reader("tenant-governance-a", "reader-a");
        PrincipalContext adminB = admin("tenant-governance-b", "admin-b");
        PrincipalContext readerB = reader("tenant-governance-b", "reader-b");
        Instant now = Instant.parse("2026-08-03T00:00:00Z");

        store.createSpace(
                adminA,
                new KnowledgeSpaceId("engineering"),
                "Engineering",
                "Engineering knowledge space",
                now
        );
        store.createSpace(
                adminA,
                new KnowledgeSpaceId("private"),
                "Private",
                "Private knowledge space",
                now
        );
        store.createSpace(
                adminB,
                new KnowledgeSpaceId("engineering"),
                "Other tenant",
                "Other tenant knowledge space",
                now
        );
        store.ensurePrincipal(readerA, now);
        store.ensurePrincipal(readerB, now);

        var roleGrant = new KnowledgeGovernanceStore.SpaceGrant(
                KnowledgeGovernanceStore.SubjectType.ROLE,
                "knowledge-reader",
                KnowledgeGovernanceStore.Permission.READ
        );
        store.grant(
                adminA.tenantId(),
                new KnowledgeSpaceId("engineering"),
                roleGrant,
                adminA.principalId(),
                now
        );

        var readerSpaces = store.accessibleSpaces(readerA);
        assertEquals(1, readerSpaces.size());
        assertEquals("engineering", readerSpaces.getFirst().id());
        assertTrue(store.accessibleSpaces(readerB).isEmpty());
        assertEquals(2, store.accessibleSpaces(adminA).size());

        assertTrue(store.revoke(
                adminA.tenantId(),
                new KnowledgeSpaceId("engineering"),
                roleGrant
        ));
        assertTrue(store.accessibleSpaces(readerA).isEmpty());
        assertFalse(store.revoke(
                adminA.tenantId(),
                new KnowledgeSpaceId("engineering"),
                roleGrant
        ));

        PrincipalContext noRole = new PrincipalContext(
                adminA.tenantId(),
                new PrincipalId("reader-without-roles"),
                Set.of(),
                Set.of(),
                false
        );
        store.ensurePrincipal(noRole, now);
        store.grant(
                adminA.tenantId(),
                new KnowledgeSpaceId("engineering"),
                new KnowledgeGovernanceStore.SpaceGrant(
                        KnowledgeGovernanceStore.SubjectType.ROLE,
                        "__none__",
                        KnowledgeGovernanceStore.Permission.READ
                ),
                adminA.principalId(),
                now
        );
        assertTrue(store.accessibleSpaces(noRole).isEmpty());

        jdbc.update(
                "UPDATE knowledge_tenant SET status = 'SUSPENDED' WHERE id = ?",
                adminA.tenantId().value()
        );
        assertTrue(store.accessibleSpaces(adminA).isEmpty());
    }

    @Test
    void provisioningUsesSpaceScopedApiConnectorAndDoesNotReactivateSuspendedPrincipal() {
        PrincipalContext admin = admin("tenant-governance-c", "admin-c");
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        store.createSpace(
                admin,
                new KnowledgeSpaceId("one"),
                "One",
                "First API connector test space",
                now
        );
        store.createSpace(
                admin,
                new KnowledgeSpaceId("two"),
                "Two",
                "Second API connector test space",
                now
        );

        Integer connectorCount = jdbc.queryForObject("""
                SELECT count(*)
                  FROM connector_instance
                 WHERE tenant_id = ?
                   AND id IN ('api-upload:one', 'api-upload:two')
                """, Integer.class, admin.tenantId().value());
        assertEquals(2, connectorCount);

        jdbc.update("""
                UPDATE knowledge_principal
                   SET status = 'SUSPENDED'
                 WHERE tenant_id = ? AND principal_id = ?
                """, admin.tenantId().value(), admin.principalId().value());
        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> store.ensurePrincipal(admin, now.plusSeconds(60))
        );
        String status = jdbc.queryForObject("""
                SELECT status
                  FROM knowledge_principal
                 WHERE tenant_id = ? AND principal_id = ?
                """, String.class, admin.tenantId().value(), admin.principalId().value());
        assertEquals("SUSPENDED", status);

        jdbc.update(
                "UPDATE knowledge_tenant SET status = 'SUSPENDED' WHERE id = ?",
                admin.tenantId().value()
        );
        assertTrue(store.accessibleSpaces(admin).isEmpty());
    }

    private static PrincipalContext admin(String tenant, String principal) {
        return new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId(principal),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }

    private static PrincipalContext reader(String tenant, String principal) {
        return new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId(principal),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );
    }
}
