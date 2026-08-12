package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.spi.management.DocumentLifecycleStore;
import dev.infinityknowledge.store.postgres.PostgresDocumentLifecycleStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the small document-lifecycle use case without expanding runtime configuration. */
@Configuration
public class DocumentLifecycleConfiguration {

    @Bean
    DocumentLifecycleStore documentLifecycleStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresDocumentLifecycleStore(
                jdbc,
                new TransactionTemplate(transactionManager)
        );
    }
}
