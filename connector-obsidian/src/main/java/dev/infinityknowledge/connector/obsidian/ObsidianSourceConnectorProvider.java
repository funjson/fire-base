package dev.infinityknowledge.connector.obsidian;

import dev.infinityknowledge.spi.connector.SourceConnector;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Opens bounded Obsidian vault connectors from persisted definitions.
 */
public final class ObsidianSourceConnectorProvider implements SourceConnectorProvider {
    public static final String TYPE = "OBSIDIAN";

    private final List<Path> allowedRoots;
    private final long maxFileBytes;
    private final Set<String> ignoredDirectoryNames;

    public ObsidianSourceConnectorProvider(
            List<Path> allowedRoots,
            long maxFileBytes,
            Set<String> ignoredDirectoryNames
    ) {
        Objects.requireNonNull(allowedRoots, "allowedRoots must not be null");
        this.allowedRoots = allowedRoots.stream()
                .map(ObsidianSourceConnectorProvider::realDirectory)
                .toList();
        if (maxFileBytes < 1 || maxFileBytes > 100_000_000L) {
            throw new IllegalArgumentException(
                    "maxFileBytes must be between 1 and 100000000"
            );
        }
        this.maxFileBytes = maxFileBytes;
        this.ignoredDirectoryNames = Set.copyOf(
                Objects.requireNonNull(
                        ignoredDirectoryNames,
                        "ignoredDirectoryNames must not be null"
                )
        );
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public SourceConnector open(SourceConnectorDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        if (!TYPE.equals(definition.type())) {
            throw new IllegalArgumentException(
                    "Obsidian provider cannot open connector type " + definition.type()
            );
        }
        Path vaultRoot = allowedVaultRoot(required(definition, "vaultPath"));
        if (!Files.isDirectory(vaultRoot)) {
            throw new IllegalArgumentException("vaultPath must be an existing directory");
        }
        return new ObsidianVaultConnector(new ObsidianConnectorConfig(
                definition.connectorId(),
                required(definition, "vaultName"),
                vaultRoot,
                maxFileBytes,
                ignoredDirectoryNames
        ));
    }

    private Path allowedVaultRoot(String rawPath) {
        try {
            Path candidate = Path.of(rawPath).toRealPath();
            boolean allowed = allowedRoots.stream().anyMatch(candidate::startsWith);
            if (!allowed) {
                throw new IllegalArgumentException(
                        "vaultPath is outside configured Obsidian roots"
                );
            }
            return candidate;
        } catch (IOException invalidPath) {
            throw new IllegalArgumentException(
                    "vaultPath must be an existing readable directory",
                    invalidPath
            );
        }
    }

    private static String required(
            SourceConnectorDefinition definition,
            String name
    ) {
        String value = definition.configuration().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Obsidian connector configuration is missing " + name
            );
        }
        return value.strip();
    }

    private static Path realDirectory(Path path) {
        try {
            Path realPath = path.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException(
                        "allowed Obsidian root must be a directory"
                );
            }
            return realPath;
        } catch (IOException invalidRoot) {
            throw new IllegalArgumentException(
                    "allowed Obsidian root must exist and be readable",
                    invalidRoot
            );
        }
    }
}
