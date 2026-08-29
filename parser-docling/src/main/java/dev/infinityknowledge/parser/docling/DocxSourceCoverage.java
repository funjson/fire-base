package dev.infinityknowledge.parser.docling;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 校验 Docling 的 DOCX 结果没有静默丢失源文件中的非空表格。 */
final class DocxSourceCoverage {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DocxSourceCoverage() {
    }

    /**
     * 每个源表格都必须由一个独立 TABLE 元素完整覆盖，匹配时只忽略空白差异。
     *
     * <p>该校验不把 POI 的解析结果混入 Docling 产物；POI 只作为源侧完整性门禁。
     * 失败信息不包含单元格正文，避免异常或日志泄露文档内容。</p>
     */
    static void requireCovered(byte[] sourceBytes, ParsedDocument parsed) {
        List<String> sourceTables = sourceTables(sourceBytes);
        if (sourceTables.isEmpty()) {
            return;
        }
        List<String> remainingDoclingTables = new ArrayList<>(parsed.elements().stream()
                .filter(element -> element.type() == ElementType.TABLE)
                .map(element -> normalize(element.content()))
                .filter(content -> !content.isEmpty())
                .toList());
        for (String sourceTable : sourceTables) {
            int coveredIndex = coveredIndex(sourceTable, remainingDoclingTables);
            if (coveredIndex < 0) {
                throw coverageMismatch();
            }
            remainingDoclingTables.remove(coveredIndex);
        }
    }

    private static List<String> sourceTables(byte[] sourceBytes) {
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(sourceBytes)
        )) {
            return document.getTables().stream()
                    .map(DocxSourceCoverage::tableText)
                    .map(DocxSourceCoverage::normalize)
                    .filter(content -> !content.isEmpty())
                    .toList();
        } catch (IOException | RuntimeException failure) {
            throw new DocumentParseException(
                    "DOCLING_SOURCE_COVERAGE_CHECK_FAILED: unable to inspect DOCX source"
            );
        }
    }

    private static int coveredIndex(String sourceTable, List<String> doclingTables) {
        for (int index = 0; index < doclingTables.size(); index++) {
            if (doclingTables.get(index).contains(sourceTable)) {
                return index;
            }
        }
        return -1;
    }

    private static String tableText(XWPFTable table) {
        return table.getRows().stream()
                .map(row -> row.getTableCells().stream()
                        .map(XWPFTableCell::getText)
                        .map(value -> value == null ? "" : value)
                        .collect(Collectors.joining("\t")))
                .collect(Collectors.joining("\n"));
    }

    private static String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.strip())
                .replaceAll(" ");
    }

    private static DocumentParseException coverageMismatch() {
        return new DocumentParseException(
                "DOCLING_SOURCE_COVERAGE_MISMATCH: DOCX table content was not preserved"
        );
    }
}
