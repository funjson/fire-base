package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuGenerationConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuGraphExtractor;
import dev.infinityknowledge.provider.zhipu.ZhipuJsonGenerationClient;
import dev.infinityknowledge.spi.graph.GraphExtractor;
import dev.infinityknowledge.spi.graph.GraphStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.store.neo4j.Neo4jGraphConfig;
import dev.infinityknowledge.store.neo4j.Neo4jGraphProjectionExecutor;
import dev.infinityknowledge.store.neo4j.Neo4jGraphRetriever;
import dev.infinityknowledge.store.neo4j.Neo4jGraphStore;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/** Wires Neo4j only when the graph channel and a real source-backed extractor are enabled. */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.graph.neo4j",
        name = "enabled",
        havingValue = "true"
)
public class Neo4jRuntimeConfiguration {

    @Bean(destroyMethod = "close")
    Driver neo4jDriver(Neo4jProperties properties) {
        return GraphDatabase.driver(
                properties.uri(),
                AuthTokens.basic(properties.username(), properties.password())
        );
    }

    @Bean
    Neo4jGraphConfig neo4jGraphConfig(Neo4jProperties properties) {
        return new Neo4jGraphConfig(
                properties.database(),
                properties.maxHops(),
                properties.maxResults()
        );
    }

    @Bean
    GraphStore graphStore(Driver driver, Neo4jGraphConfig config) {
        Neo4jGraphStore store = new Neo4jGraphStore(
                driver,
                config,
                JsonMapper.builder().build()
        );
        store.ensureSchema();
        return store;
    }

    @Bean
    GraphExtractor graphExtractor(
            Neo4jProperties properties,
            IndexingProperties indexingProperties
    ) {
        Duration requiredLease = properties.maximumExtractionDuration()
                .plusSeconds(30);
        if (indexingProperties.leaseDuration().compareTo(requiredLease) < 0) {
            throw new IllegalStateException(
                    "projection leaseDuration must cover the graph extraction retry budget "
                            + "plus 30 seconds"
            );
        }
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(properties.extractionTimeout());
        if (!properties.proxyHost().isEmpty()) {
            http.proxy(ProxySelector.of(new InetSocketAddress(
                    properties.proxyHost(),
                    properties.proxyPort()
            )));
        }
        var config = new ZhipuGenerationConfig(
                properties.extractionEndpoint(),
                properties.extractionApiKey(),
                properties.extractionModel(),
                properties.extractionTimeout(),
                properties.extractionMaxAttempts(),
                properties.extractionInitialBackoff(),
                properties.extractionMaxInputCharacters(),
                properties.extractionMaxOutputTokens()
        );
        return new ZhipuGraphExtractor(new ZhipuJsonGenerationClient(
                config,
                http.build(),
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        ));
    }

    @Bean
    ProjectionExecutor graphProjectionExecutor(
            GraphExtractor extractor,
            GraphStore graphStore,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        return new Neo4jGraphProjectionExecutor(
                extractor,
                graphStore,
                activeRevisionGuard
        );
    }

    @Bean
    Retriever graphRetriever(
            GraphStore graphStore,
            ActiveRevisionGuard activeRevisionGuard,
            Neo4jGraphConfig config
    ) {
        return new Neo4jGraphRetriever(graphStore, activeRevisionGuard, config);
    }
}
