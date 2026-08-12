package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.store.postgres.PostgresKnowledgeGovernanceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigurationContractTest {

    private static final Set<String> CONSOLE_ORIGINS = Set.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    );

    @Test
    void acceptanceProfileEnablesStrictHybridRuntime() throws Exception {
        var sources = new YamlPropertySourceLoader().load(
                "acceptance",
                new ClassPathResource("application-acceptance.yml")
        );
        assertThat(sources).hasSize(1);
        var source = sources.getFirst();

        assertThat(source.getProperty("spring.config.activate.on-profile"))
                .isEqualTo("acceptance");
        assertThat(source.getProperty("spring.datasource.url").toString())
                .contains("jdbc:postgresql:");
        assertThat(source.getProperty("infinity.knowledge.retrieval.mode"))
                .isEqualTo("hybrid");
        assertThat(source.getProperty("infinity.knowledge.vector.enabled"))
                .isEqualTo(true);
        assertThat(source.getProperty(
                "infinity.knowledge.keyword.elasticsearch.enabled"
        )).isEqualTo(true);
        assertThat(source.getProperty("infinity.knowledge.embedding.api-key"))
                .isEqualTo("${ZHIPU_API_KEY}");
    }

    @Test
    void resourceServerRequiresTheKnowledgeApiAudience() throws Exception {
        var sources = new YamlPropertySourceLoader().load(
                "application",
                new ClassPathResource("application.yml")
        );
        assertThat(sources).hasSize(1);
        var source = sources.getFirst();

        assertThat(source.getProperty(
                "spring.security.oauth2.resourceserver.jwt.audiences[0]"
        )).isEqualTo("${OIDC_AUDIENCE:infinity-knowledge-api}");
        assertThat(source.getProperty("spring.task.scheduling.pool.size"))
                .isEqualTo("${KNOWLEDGE_SCHEDULER_POOL_SIZE:3}");
        assertThat(source.getProperty(
                "infinity.knowledge.connectors.obsidian.batch-size"
        )).isEqualTo("${KNOWLEDGE_OBSIDIAN_BATCH_SIZE:25}");
        assertThat(source.getProperty("infinity.knowledge.async.lease-duration"))
                .isEqualTo("2m");
    }

    @Test
    void keycloakDefinitionsStayParseableAndConsistent() throws Exception {
        Path keycloak = projectRoot().resolve("docker/keycloak");
        JsonMapper mapper = JsonMapper.builder().build();
        try (var definitions = Files.list(keycloak)) {
            for (Path definition : definitions
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList()) {
                assertThat(mapper.readTree(Files.readString(definition)))
                        .as(definition.getFileName().toString())
                        .isNotNull();
            }
        }

        JsonNode realm = mapper.readTree(Files.readString(
                keycloak.resolve("realm-infinity-knowledge.json")
        ));
        JsonNode console = mapper.readTree(Files.readString(
                keycloak.resolve("infinity-knowledge-console-client.json")
        ));
        JsonNode api = mapper.readTree(Files.readString(
                keycloak.resolve("infinity-knowledge-api-client.json")
        ));
        JsonNode audienceMapper = mapper.readTree(Files.readString(
                keycloak.resolve("api-audience-mapper.json")
        ));
        assertThat(textValues(console.path("webOrigins")))
                .containsExactlyInAnyOrderElementsOf(CONSOLE_ORIGINS);
        assertThat(textValues(console.path("redirectUris")))
                .containsExactlyInAnyOrder(
                        "http://localhost:5173/*",
                        "http://127.0.0.1:5173/*"
                );

        JsonNode realmConsole = findBy(
                realm.path("clients"),
                "clientId",
                "infinity-knowledge-console"
        );
        assertThat(textValues(realmConsole.path("webOrigins")))
                .containsExactlyInAnyOrderElementsOf(CONSOLE_ORIGINS);
        assertThat(findBy(
                realm.path("clients"),
                "clientId",
                "infinity-knowledge-cli"
        )).isNotNull();
        JsonNode realmApi = findBy(
                realm.path("clients"),
                "clientId",
                "infinity-knowledge-api"
        );
        assertThat(api.path("bearerOnly").asBoolean()).isTrue();
        assertThat(api.path("publicClient").asBoolean()).isFalse();
        assertThat(realmApi.path("bearerOnly").asBoolean()).isTrue();
        assertThat(audienceMapper.path("protocolMapper").asString())
                .isEqualTo("oidc-audience-mapper");
        assertThat(audienceMapper.path("config")
                .path("included.client.audience").asString())
                .isEqualTo("infinity-knowledge-api");
        assertRequiredMappers(realmConsole);
        assertRequiredMappers(findBy(
                realm.path("clients"),
                "clientId",
                "infinity-knowledge-cli"
        ));
        assertThat(findBy(
                realm.path("users"),
                "username",
                "demo-reader"
        )).isNotNull();
        assertThat(findBy(
                realm.path("users"),
                "username",
                "demo-admin"
        )).isNotNull();
    }

    @Test
    void keycloakConfigJobReconcilesAllRequiredObjects() throws Exception {
        String script = Files.readString(
                projectRoot().resolve("docker/keycloak/configure-realm.sh")
        );

        assertThat(script)
                .contains(
                        "upsert_role knowledge-reader",
                        "upsert_role knowledge-admin",
                        "infinity-knowledge-console",
                        "infinity-knowledge-cli",
                        "infinity-knowledge-api",
                        "upsert_mapper",
                        "api-audience",
                        "api-audience-mapper.json",
                        "demo-reader",
                        "demo-admin",
                        "set-password",
                        "ensure_realm_role",
                        "remove_realm_role"
                )
                .contains("--merge")
                .doesNotContain("awk");
    }

    @Test
    void transactionalGovernanceAdapterRemainsProxyable() {
        assertThat(Modifier.isFinal(
                PostgresKnowledgeGovernanceStore.class.getModifiers()
        )).isFalse();
    }

    private static void assertRequiredMappers(JsonNode client) {
        assertThat(findBy(
                client.path("protocolMappers"),
                "name",
                "tenant-id"
        )).isNotNull();
        assertThat(findBy(
                client.path("protocolMappers"),
                "name",
                "departments"
        )).isNotNull();
        JsonNode mapper = findBy(
                client.path("protocolMappers"),
                "name",
                "api-audience"
        );
        assertThat(mapper.path("protocolMapper").asString())
                .isEqualTo("oidc-audience-mapper");
        assertThat(mapper.path("config")
                .path("included.client.audience").asString())
                .isEqualTo("infinity-knowledge-api");
        assertThat(mapper.path("config").path("access.token.claim").asString())
                .isEqualTo("true");
    }

    private static Set<String> textValues(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static JsonNode findBy(
            JsonNode values,
            String field,
            String expected
    ) {
        for (JsonNode value : values) {
            if (expected.equals(value.path(field).asString())) {
                return value;
            }
        }
        throw new AssertionError(
                "Unable to find " + field + "=" + expected
        );
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("docker-compose.yml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("project root is not available");
        }
        return current;
    }
}
