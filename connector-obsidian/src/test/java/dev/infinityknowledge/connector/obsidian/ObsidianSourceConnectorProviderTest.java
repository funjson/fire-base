package dev.infinityknowledge.connector.obsidian;

import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObsidianSourceConnectorProviderTest {

    @TempDir
    private Path root;

    @Test
    void opensConnectorWithinAllowedRoot() {
        var provider = provider();

        var connector = provider.open(definition(root));

        assertEquals("notes", connector.connectorId());
    }

    @Test
    void rejectsVaultOutsideAllowedRoot() {
        Path outside = root.resolveSibling("outside-vault");
        var provider = provider();

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.open(definition(outside))
        );
    }

    @Test
    void rejectsDefinitionForAnotherProviderType() {
        var definition = new SourceConnectorDefinition(
                "notes",
                new KnowledgeSpaceId("engineering"),
                "NOTION",
                "Notes",
                70,
                Map.of("vaultName", "Notes", "vaultPath", root.toString())
        );

        assertThrows(IllegalArgumentException.class, () -> provider().open(definition));
    }

    @Test
    void rejectsSymbolicLinkThatEscapesAllowedRoot(@TempDir Path outside)
            throws IOException {
        Path link = root.resolve("linked-vault");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            assumeTrue(false, "symbolic links are unavailable: " + unsupported.getMessage());
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> provider().open(definition(link))
        );
    }

    private ObsidianSourceConnectorProvider provider() {
        return new ObsidianSourceConnectorProvider(
                java.util.List.of(root),
                1_048_576L,
                Set.of(".obsidian", ".git")
        );
    }

    private SourceConnectorDefinition definition(Path path) {
        return new SourceConnectorDefinition(
                "notes",
                new KnowledgeSpaceId("engineering"),
                "OBSIDIAN",
                "Notes",
                70,
                Map.of("vaultName", "Notes", "vaultPath", path.toString())
        );
    }
}
