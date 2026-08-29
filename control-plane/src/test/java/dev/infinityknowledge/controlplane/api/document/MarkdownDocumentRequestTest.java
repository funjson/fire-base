package dev.infinityknowledge.controlplane.api.document;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 HTTP DTO 只处理结构约束，协议白名单由配置化摄取策略处理。
 */
class MarkdownDocumentRequestTest {

    @Test
    void leavesSourceSchemeValidationToIngestionPolicy() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertEquals(
                    0,
                    validator.validate(request("confluence://space/page")).size()
            );
        }
    }

    @Test
    void rejectsBlankSourceUriBeforePolicyValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertEquals(1, validator.validate(request(" ")).size());
        }
    }

    @Test
    void rejectsBlankMetadataValuesAtTheHttpBoundary() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new MarkdownDocumentRequest(
                    "engineering",
                    "metadata-test.md",
                    "Metadata Test",
                    "https://example.test/metadata-test.md",
                    "zh-CN",
                    80,
                    "# Test",
                    Map.of("owner", " ")
            );

            assertEquals(1, validator.validate(request).size());
        }
    }

    private static MarkdownDocumentRequest request(String sourceUri) {
        return new MarkdownDocumentRequest(
                "engineering",
                "source-uri-test.md",
                "Source URI Test",
                sourceUri,
                "zh-CN",
                80,
                "# Test",
                Map.of()
        );
    }
}
