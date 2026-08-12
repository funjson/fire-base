package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.compiler.ExtractiveKnowledgePageCompiler;
import dev.infinityknowledge.compiler.GenerativeKnowledgePageCompiler;
import dev.infinityknowledge.compiler.KnowledgePageService;
import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuGenerationConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuJsonGenerationClient;
import dev.infinityknowledge.provider.zhipu.ZhipuPageSynthesisProvider;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.KnowledgePageSourceStore;
import dev.infinityknowledge.spi.wiki.KnowledgePageStore;
import dev.infinityknowledge.store.postgres.PostgresKnowledgePageSourceStore;
import dev.infinityknowledge.store.postgres.PostgresKnowledgePageStore;
import dev.infinityknowledge.store.postgres.PostgresPublishedPageRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

/** Wires the low-complexity Wiki compiler, persistence and published-page retriever. */
@Configuration
public class WikiRuntimeConfiguration {

    @Bean
    KnowledgePageStore knowledgePageStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresKnowledgePageStore(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }

    @Bean
    KnowledgePageSourceStore knowledgePageSourceStore(JdbcTemplate jdbc) {
        return new PostgresKnowledgePageSourceStore(jdbc);
    }

    @Bean
    KnowledgePageCompiler knowledgePageCompiler(WikiProperties properties) {
        if (!properties.generativeEnabled()) {
            return new ExtractiveKnowledgePageCompiler();
        }
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout());
        if (!properties.proxyHost().isEmpty()) {
            http.proxy(ProxySelector.of(new InetSocketAddress(
                    properties.proxyHost(),
                    properties.proxyPort()
            )));
        }
        ZhipuGenerationConfig config = new ZhipuGenerationConfig(
                properties.endpoint(),
                properties.apiKey(),
                properties.model(),
                properties.requestTimeout(),
                properties.maxAttempts(),
                properties.initialBackoff(),
                properties.maxInputCharacters(),
                properties.maxOutputTokens()
        );
        var client = new ZhipuJsonGenerationClient(
                config,
                http.build(),
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );
        return new GenerativeKnowledgePageCompiler(
                new ZhipuPageSynthesisProvider(client),
                "glm-wiki-v1",
                properties.model(),
                properties.minimumSourceCoverage()
        );
    }

    @Bean
    KnowledgePageService knowledgePageService(
            KnowledgePageCompiler compiler,
            KnowledgePageStore store
    ) {
        return new KnowledgePageService(compiler, store);
    }

    @Bean
    Retriever publishedPageRetriever(NamedParameterJdbcTemplate jdbc) {
        return new PostgresPublishedPageRetriever(jdbc);
    }
}
