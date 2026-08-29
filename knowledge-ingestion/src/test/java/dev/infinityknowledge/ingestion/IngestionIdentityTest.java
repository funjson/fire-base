package dev.infinityknowledge.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionIdentityTest {

    @Test
    void preservesPersistedDocumentIdentityAlgorithm() {
        DocumentId documentId = IngestionIdentity.documentId(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("ops"),
                new SourceDescriptor(
                        "api-upload:ops",
                        SourceType.API,
                        "employee-handbook",
                        "https://knowledge.example/employee-handbook",
                        Map.of()
                )
        );

        assertThat(documentId.value())
                .isEqualTo(UUID.fromString("6e58f10a-e1a2-341d-813b-d8a7072fee76"));
    }

    @Test
    void preservesPersistedRevisionIdentityAlgorithm() {
        DocumentId documentId = new DocumentId(
                UUID.fromString("6e58f10a-e1a2-341d-813b-d8a7072fee76")
        );

        UUID revisionId = IngestionIdentity.revisionId(
                documentId,
                "26c60a61d01db5836ca70fefd44a6a016620413c8ef5f259a6c5612d4f79d3b8",
                "text/markdown",
                "zh-CN",
                "markdown-structure-v1:heading-aware-v2:1200:2000:spans=128"
        );

        assertThat(revisionId)
                .isEqualTo(UUID.fromString("f5b6a695-9792-3345-a697-ffc8ceeeedf2"));
    }

    @Test
    void preservesContentHashAlgorithmForTextAndBytes() {
        String content = "hello\nworld";
        String expected = "26c60a61d01db5836ca70fefd44a6a016620413c8ef5f259a6c5612d4f79d3b8";

        assertThat(IngestionIdentity.sha256(content)).isEqualTo(expected);
        assertThat(IngestionIdentity.sha256(content.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(expected);
    }

    @Test
    void normalizesBcp47LanguageWithoutChangingExistingContract() {
        assertThat(IngestionIdentity.normalizeLanguageTag("zh-cn")).isEqualTo("zh-CN");
        assertThat(IngestionIdentity.normalizeLanguageTag(" en-US ")).isEqualTo("en-US");
        assertThatThrownBy(() -> IngestionIdentity.normalizeLanguageTag("en_US"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BCP 47");
    }
}
