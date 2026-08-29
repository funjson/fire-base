package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.BoundaryExpectation;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.ExpectedElement;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.HardGates;
import dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.Review;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 从可审查的 JSON 清单和独立 Golden Source 文件加载抽取验收数据。
 *
 * <p>Source 文件与标签分离，评审人员可以直接打开 Markdown/PDF/DOCX 原件；
 * 相对路径必须位于清单目录内，避免误读工作区之外的文件。</p>
 */
public final class ExtractionDatasetLoader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 加载数据集清单，并将相对 Source 文件物化为不可变 Case。 */
    public ExtractionAcceptanceDataset loadDataset(Path manifest) throws IOException {
        Path normalizedManifest = normalizeFile(manifest, "dataset manifest");
        DatasetFile data = JSON.readValue(
                Files.readString(normalizedManifest, StandardCharsets.UTF_8),
                DatasetFile.class
        );
        Path corpusRoot = normalizedManifest.getParent();
        return materialize(data, resource -> {
            Path resolved = resolveWithin(corpusRoot, resource);
            try {
                return Files.readAllBytes(resolved);
            } catch (IOException failure) {
                throw new DatasetResourceException(failure);
            }
        });
    }

    /**
     * 从应用 Classpath 加载可执行 Dataset，供 Space 测试广场和离线 CI 复用同一语料。
     *
     * @param manifestResource 不带开头斜杠的 Classpath 清单路径
     * @param classLoader 明确拥有 knowledge-evaluation 资源的类加载器
     */
    public ExtractionAcceptanceDataset loadDataset(
            String manifestResource,
            ClassLoader classLoader
    ) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        String manifest = safeClasspathResource(manifestResource);
        DatasetFile data;
        try (InputStream input = classLoader.getResourceAsStream(manifest)) {
            if (input == null) {
                throw new IOException("dataset manifest was not found on classpath");
            }
            data = JSON.readValue(input.readAllBytes(), DatasetFile.class);
        }
        int separator = manifest.lastIndexOf('/');
        String root = separator < 0 ? "" : manifest.substring(0, separator + 1);
        return materialize(data, resource -> {
            String resolved = root + safeRelativeResource(resource);
            try (InputStream input = classLoader.getResourceAsStream(resolved)) {
                if (input == null) {
                    throw new IOException("dataset resource was not found on classpath");
                }
                return input.readAllBytes();
            } catch (IOException failure) {
                throw new DatasetResourceException(failure);
            }
        });
    }

    private static ExtractionAcceptanceDataset materialize(
            DatasetFile data,
            Function<String, byte[]> resourceReader
    ) throws IOException {
        Objects.requireNonNull(data, "dataset manifest must not be empty");
        List<ExtractionAcceptanceDataset.Case> cases = new ArrayList<>();
        for (CaseFile value : Objects.requireNonNull(data.cases(), "cases must not be null")) {
            byte[] sourceBytes;
            byte[] artifactBytes;
            try {
                sourceBytes = resourceReader.apply(value.sourceResource());
                artifactBytes = resourceReader.apply(value.artifactResource());
            } catch (DatasetResourceException failure) {
                throw failure.ioFailure();
            }
            String actualHash = sha256(sourceBytes);
            if (!actualHash.equals(value.sourceSha256())) {
                throw new IOException(
                        "golden source checksum does not match manifest: "
                                + value.sourceResource()
                );
            }
            String actualArtifactHash = sha256(artifactBytes);
            if (!actualArtifactHash.equals(value.artifactSha256())) {
                throw new IOException(
                        "golden artifact checksum does not match manifest: "
                                + value.artifactResource()
                );
            }
            cases.add(new ExtractionAcceptanceDataset.Case(
                    value.id(),
                    value.description(),
                    value.sourceResource(),
                    sourceBytes,
                    actualHash,
                    decodeUtf8(artifactBytes, value.artifactResource()),
                    actualArtifactHash,
                    value.artifactContract(),
                    value.license(),
                    value.review(),
                    value.mediaType(),
                    value.expectedElements(),
                    value.boundaries(),
                    value.mustPreserve(),
                    value.mustRemove(),
                    value.hardGates()
            ));
        }
        return new ExtractionAcceptanceDataset(data.datasetVersion(), cases);
    }

    private static Path normalizeFile(Path value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.toAbsolutePath().normalize();
    }

    private static Path resolveWithin(Path corpusRoot, String resource) {
        String safeResource = safeRelativeResource(resource);
        Path source = corpusRoot.resolve(safeResource).normalize();
        if (!source.startsWith(corpusRoot)) {
            throw new IllegalArgumentException("sourceResource must stay inside corpus directory");
        }
        return source;
    }

    private static String safeClasspathResource(String value) {
        Objects.requireNonNull(value, "manifestResource must not be null");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.isBlank()
                || normalized.contains("../") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("manifestResource must be a safe classpath path");
        }
        return normalized;
    }

    private static String safeRelativeResource(String value) {
        Objects.requireNonNull(value, "dataset resource must not be null");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.isBlank()
                || normalized.contains("../") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("dataset resource must stay inside corpus root");
        }
        return normalized;
    }

    /** 严格解码 UTF-8，避免替换字符改变 SourceRange 却未触发失败。 */
    private static String decodeUtf8(byte[] sourceBytes, String sourceName)
            throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sourceBytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("golden source is not valid UTF-8: " + sourceName, failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** JSON 清单的外层传输结构。 */
    private record DatasetFile(String datasetVersion, List<CaseFile> cases) {
    }

    /** JSON Case 只保存 Source 相对路径，正文在加载阶段读取。 */
    private record CaseFile(
            String id,
            String description,
            String sourceResource,
            String sourceSha256,
            String artifactResource,
            String artifactSha256,
            String artifactContract,
            String license,
            Review review,
            String mediaType,
            List<ExpectedElement> expectedElements,
            List<BoundaryExpectation> boundaries,
            List<SourceRange> mustPreserve,
            List<SourceRange> mustRemove,
            HardGates hardGates
    ) {
    }

    /** 允许 lambda 保留 IOException，并在物化边界恢复受检异常。 */
    private static final class DatasetResourceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IOException ioFailure;

        private DatasetResourceException(IOException ioFailure) {
            super(null, ioFailure, false, false);
            this.ioFailure = Objects.requireNonNull(ioFailure, "ioFailure must not be null");
        }

        private IOException ioFailure() {
            return ioFailure;
        }
    }
}
