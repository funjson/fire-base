package dev.infinityknowledge.ingestion.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次确定的 Parser 选择。
 *
 * <p>对象内部保留真正 Parser，但只暴露稳定格式、处理契约和受预算保护的解析操作，
 * 避免上层在计算修订身份后再次访问 Registry 重新选择。</p>
 */
public final class ParserSelection {

    private final DocumentParser parser;
    private final DocumentFormat format;
    private final String contract;

    ParserSelection(DocumentParser parser, DocumentFormat format) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.contract = DocumentParserRegistry.selectedParserContract(parser);
    }

    /** 返回规范格式和 Parser 版本。 */
    public DocumentFormat format() {
        return format;
    }

    /** 返回进入修订处理指纹的稳定契约。 */
    public String contract() {
        return contract;
    }

    /** 使用已经有界读取的来源字节完成解析。 */
    public ParsedDocument parse(
            UUID revisionId,
            String fileName,
            byte[] sourceBytes,
            DocumentParseLimits limits
    ) {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(sourceBytes, "sourceBytes must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (sourceBytes.length > limits.maximumSourceBytes()) {
            throw new DocumentParseException("source exceeds maximumSourceBytes");
        }
        return parseSelected(revisionId, fileName, sourceBytes, limits);
    }

    /** 在硬大小预算内读取来源流并完成解析。 */
    public ParsedDocument parse(
            UUID revisionId,
            String fileName,
            InputStream source,
            long declaredLength,
            DocumentParseLimits limits
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (declaredLength < -1) {
            throw new IllegalArgumentException("declaredLength must be -1 or non-negative");
        }
        if (declaredLength > limits.maximumSourceBytes()) {
            throw new DocumentParseException("source exceeds maximumSourceBytes");
        }
        byte[] bytes = readBounded(source, limits.maximumSourceBytes());
        if (declaredLength >= 0 && declaredLength != bytes.length) {
            throw new DocumentParseException("source length does not match declaredLength");
        }
        return parse(revisionId, fileName, bytes, limits);
    }

    private ParsedDocument parseSelected(
            UUID revisionId,
            String fileName,
            byte[] sourceBytes,
            DocumentParseLimits limits
    ) {
        ParsedDocument parsed = parser.parse(new DocumentParseInput(
                revisionId,
                format.mediaType(),
                fileName,
                sourceBytes,
                limits
        ));
        if (!format.parserId().equals(parsed.parserId())
                || !format.parserVersion().equals(parsed.parserVersion())) {
            throw new DocumentParseException(
                    "parser output identity differs from the selected parser contract"
            );
        }
        return parsed;
    }

    private static byte[] readBounded(InputStream source, int maximumBytes) {
        try {
            byte[] bytes = source.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new DocumentParseException("source exceeds maximumSourceBytes");
            }
            return bytes;
        } catch (IOException failure) {
            throw new DocumentParseException("failed to read source document", failure);
        }
    }
}
