package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.ingestion.parser.DocumentParser;
import dev.infinityknowledge.parser.docling.DoclingParserRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅在部署显式启用时安装 Docling Serve 客户端及已通过 Golden 的格式 Parser。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DoclingParserProperties.class)
public class DoclingParserConfiguration {

    /** 创建由 PDF、DOCX Parser 共享的无正文日志客户端。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.ingestion.parsers.docling",
            name = "enabled",
            havingValue = "true"
    )
    DoclingParserRuntime doclingParserRuntime(DoclingParserProperties properties) {
        return DoclingParserRuntime.connect(
                properties.endpoint(),
                properties.apiKey(),
                properties.deploymentContract(),
                properties.serverContract(),
                properties.connectTimeout(),
                properties.documentTimeout(),
                properties.readTimeout(),
                properties.maximumConcurrentRequests()
        );
    }

    /** 安装稳定 ID 为 {@code docling-serve-pdf} 的 PDF Parser。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.ingestion.parsers.docling",
            name = "enabled",
            havingValue = "true"
    )
    DocumentParser doclingPdfDocumentParser(DoclingParserRuntime runtime) {
        return runtime.pdfParser();
    }

    /** 安装稳定 ID 为 {@code docling-serve-docx} 的 DOCX Parser。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.ingestion.parsers.docling",
            name = {"enabled", "docx-enabled"},
            havingValue = "true"
    )
    DocumentParser doclingDocxDocumentParser(DoclingParserRuntime runtime) {
        return runtime.docxParser();
    }
}
