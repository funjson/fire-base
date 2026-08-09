package dev.infinityknowledge.controlplane.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证来源 URI 协议白名单可配置且拒绝非绝对 URI。
 */
class IngestionPropertiesTest {

    @Test
    void acceptsConfiguredSchemesCaseInsensitively() {
        var properties = new IngestionProperties(
                512,
                1_024,
                List.of("HTTPS", "obsidian", "confluence")
        );

        assertTrue(properties.allowsSourceUri("https://kb.example.invalid/page"));
        assertTrue(properties.allowsSourceUri("obsidian://vault/folder/page.md"));
        assertTrue(properties.allowsSourceUri("confluence://space/page"));
    }

    @Test
    void rejectsRelativeMalformedAndUnknownSources() {
        var properties = new IngestionProperties(
                512,
                1_024,
                List.of("https")
        );

        assertFalse(properties.allowsSourceUri("/local/page.md"));
        assertFalse(properties.allowsSourceUri("https://bad uri"));
        assertFalse(properties.allowsSourceUri("https:relative-path"));
        assertFalse(properties.allowsSourceUri("https://user:secret@example.test/page"));
        assertFalse(properties.allowsSourceUri("javascript:alert(1)"));
    }

    @Test
    void rejectsMalformedConfiguredSchemesAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IngestionProperties(
                        512,
                        1_024,
                        List.of("https", "not a scheme")
                )
        );
    }
}
