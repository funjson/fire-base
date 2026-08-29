package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.NormalizedDocumentArtifact;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 将文本原件严格解码为 LF 规范化制品，并保留逐行 UTF-16 坐标。 */
final class NormalizedTextSource {

    static final String CONTRACT = "utf8-line-endings-lf-v1";

    private final NormalizedDocumentArtifact artifact;
    private final List<Line> lines;

    private NormalizedTextSource(NormalizedDocumentArtifact artifact, List<Line> lines) {
        this.artifact = artifact;
        this.lines = lines;
    }

    /** 严格拒绝非法 UTF-8，并只规范化 CRLF/CR 换行，不改写其他正文。 */
    static NormalizedTextSource decode(byte[] sourceBytes, String formatName) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sourceBytes))
                    .toString();
            String normalized = decoded.replace("\r\n", "\n").replace('\r', '\n');
            NormalizedDocumentArtifact artifact = NormalizedDocumentArtifact.create(
                    CONTRACT,
                    normalized
            );
            return new NormalizedTextSource(artifact, scanLines(normalized));
        } catch (CharacterCodingException failure) {
            throw new DocumentParseException(formatName + " source must be valid UTF-8", failure);
        }
    }

    NormalizedDocumentArtifact artifact() {
        return artifact;
    }

    List<Line> lines() {
        return lines;
    }

    /** 按 Java {@link String#strip()} 语义收缩范围，但不复制或改变范围内正文。 */
    TextRange strip(int startOffset, int endOffset) {
        int start = startOffset;
        int end = endOffset;
        String text = artifact.text();
        while (start < end) {
            int codePoint = text.codePointAt(start);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = text.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return new TextRange(start, end);
    }

    private static List<Line> scanLines(String text) {
        List<Line> values = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                values.add(new Line(text.substring(start, index), start, index));
                start = index + 1;
            }
        }
        if (start <= text.length()) {
            values.add(new Line(text.substring(start), start, text.length()));
        }
        return List.copyOf(values);
    }

    /** 一行不含换行符的正文及其绝对坐标。 */
    record Line(String text, int startOffset, int endOffset) {
    }

    /** 允许空范围的中间扫描结果；加入 Element 前必须确保非空。 */
    record TextRange(int startOffset, int endOffset) {
        boolean isEmpty() {
            return startOffset >= endOffset;
        }
    }
}
