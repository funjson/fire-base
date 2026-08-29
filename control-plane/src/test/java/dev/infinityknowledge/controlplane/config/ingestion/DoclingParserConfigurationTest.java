package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.ingestion.parser.DocumentParser;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.ingestion.parser.DocxDocumentParser;
import dev.infinityknowledge.ingestion.parser.PdfDocumentParser;
import dev.infinityknowledge.parser.docling.DoclingParserRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证外部 Parser 只在部署启用后进入统一注册表的 Bean 来源。 */
class DoclingParserConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DoclingParserConfiguration.class);

    @Test
    void remainsAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DoclingParserRuntime.class);
            assertThat(context).doesNotHaveBean("doclingPdfDocumentParser");
            assertThat(context).doesNotHaveBean("doclingDocxDocumentParser");
        });
    }

    @Test
    void installsOnlyPdfParserWhenDoclingIsEnabledByDefault() {
        contextRunner.withPropertyValues(
                "infinity.knowledge.ingestion.parsers.docling.enabled=true",
                "infinity.knowledge.ingestion.parsers.docling.endpoint=http://localhost:5001",
                "infinity.knowledge.ingestion.parsers.docling.deployment-contract=docling-serve-v1.20.0@sha256:0123abcd",
                "infinity.knowledge.ingestion.parsers.docling.server-contract=DoclingDocument@1.7.0",
                "infinity.knowledge.ingestion.parsers.docling.connect-timeout=5s",
                "infinity.knowledge.ingestion.parsers.docling.document-timeout=30s",
                "infinity.knowledge.ingestion.parsers.docling.read-timeout=40s",
                "infinity.knowledge.ingestion.parsers.docling.maximum-concurrent-requests=2"
        ).run(context -> {
            assertThat(context).hasSingleBean(DoclingParserRuntime.class);
            assertThat(context).hasBean("doclingPdfDocumentParser");
            assertThat(context).doesNotHaveBean("doclingDocxDocumentParser");
            assertThat(context.getBeansOfType(DocumentParser.class).values())
                    .extracting(DocumentParser::id)
                    .containsExactly("docling-serve-pdf");

            var installed = new ArrayList<>(
                    context.getBeansOfType(DocumentParser.class).values()
            );
            installed.add(new PdfDocumentParser());
            installed.add(new DocxDocumentParser());
            var registry = new DocumentParserRegistry(installed, Map.of(
                    "application/pdf",
                    "pdfbox-page",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "poi-docx-structure"
            ));
            assertThat(registry.capabilities())
                    .filteredOn(value -> value.canonicalMediaType().equals("application/pdf"))
                    .extracting(value -> Map.entry(value.parserId(), value.defaultSelection()))
                    .containsExactlyInAnyOrder(
                            Map.entry("pdfbox-page", true),
                            Map.entry("docling-serve-pdf", false)
                    );
            assertThat(registry.capabilities())
                    .filteredOn(value -> value.canonicalMediaType().contains("wordprocessingml"))
                    .extracting(value -> Map.entry(value.parserId(), value.defaultSelection()))
                    .containsExactly(Map.entry("poi-docx-structure", true));
        });
    }

    @Test
    void installsDocxParserOnlyAfterItsIndependentGateIsEnabled() {
        contextRunner.withPropertyValues(
                "infinity.knowledge.ingestion.parsers.docling.enabled=true",
                "infinity.knowledge.ingestion.parsers.docling.docx-enabled=true",
                "infinity.knowledge.ingestion.parsers.docling.endpoint=http://localhost:5001",
                "infinity.knowledge.ingestion.parsers.docling.deployment-contract="
                        + "docling-serve-v1.20.0@sha256:0123abcd",
                "infinity.knowledge.ingestion.parsers.docling.server-contract="
                        + "DoclingDocument@1.10.0",
                "infinity.knowledge.ingestion.parsers.docling.connect-timeout=5s",
                "infinity.knowledge.ingestion.parsers.docling.document-timeout=30s",
                "infinity.knowledge.ingestion.parsers.docling.read-timeout=40s"
        ).run(context -> {
            assertThat(context).hasBean("doclingPdfDocumentParser");
            assertThat(context).hasBean("doclingDocxDocumentParser");
            assertThat(context.getBeansOfType(DocumentParser.class).values())
                    .extracting(DocumentParser::id)
                    .containsExactlyInAnyOrder(
                            "docling-serve-pdf",
                            "docling-serve-docx"
                    );
        });
    }

    @Test
    void rejectsDocxGateWithoutDoclingRuntime() {
        contextRunner.withPropertyValues(
                "infinity.knowledge.ingestion.parsers.docling.docx-enabled=true"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsEnabledDeploymentWithoutPinnedContracts() {
        contextRunner.withPropertyValues(
                "infinity.knowledge.ingestion.parsers.docling.enabled=true",
                "infinity.knowledge.ingestion.parsers.docling.endpoint=http://localhost:5001"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsReadTimeoutWithoutTransportMargin() {
        contextRunner.withPropertyValues(
                "infinity.knowledge.ingestion.parsers.docling.enabled=true",
                "infinity.knowledge.ingestion.parsers.docling.endpoint=http://localhost:5001",
                "infinity.knowledge.ingestion.parsers.docling.deployment-contract=docling-v1",
                "infinity.knowledge.ingestion.parsers.docling.server-contract=DoclingDocument@1.7.0",
                "infinity.knowledge.ingestion.parsers.docling.document-timeout=30s",
                "infinity.knowledge.ingestion.parsers.docling.read-timeout=35s"
        ).run(context -> assertThat(context).hasFailed());
    }
}
