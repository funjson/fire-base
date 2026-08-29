package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.ingestion.parser.NormalizedTextSource.Line;
import dev.infinityknowledge.ingestion.parser.NormalizedTextSource.TextRange;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 UTF-8 Markdown 解析为保留标题路径的结构元素。
 *
 * <p>解析器直接保留代码、表格和 Obsidian 扩展文本，不先转换成 HTML；
 * 资源预算和确定性元素标识统一由 {@link ElementAccumulator} 执行。</p>
 */
public final class MarkdownDocumentParser implements DocumentParser {

    /** 当前解析契约版本。 */
    public static final String VERSION = "markdown-structure-v3";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+.+$");

    @Override
    public String id() {
        return "markdown-structure";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String canonicalMediaType() {
        return "text/markdown";
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("text/markdown");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(".md", ".markdown");
    }

    /** Markdown 解析保留标题路径；表格当前只保证提供扁平检索文本。 */
    @Override
    public Set<ParserOutputCapability> outputCapabilities() {
        return Set.of(
                ParserOutputCapability.STANDARD_ELEMENTS,
                ParserOutputCapability.HIERARCHY,
                ParserOutputCapability.FLAT_TABLE_TEXT
        );
    }

    /**
     * 在统一资源预算内解析一个 Markdown 修订。
     */
    @Override
    public ParsedDocument parse(DocumentParseInput input) {
        NormalizedTextSource source = NormalizedTextSource.decode(
                input.sourceBytes(),
                "Markdown"
        );
        List<Line> lines = source.lines();
        ElementAccumulator elements = new ElementAccumulator(
                input.revisionId(),
                input.limits(),
                source.artifact()
        );
        FrontMatter frontMatter = frontMatter(lines, source);
        int index = 0;
        if (frontMatter != null) {
            if (!frontMatter.range().isEmpty()) {
                elements.addFromArtifact(
                        ElementType.PARAGRAPH,
                        frontMatter.range().startOffset(),
                        frontMatter.range().endOffset(),
                        Map.of("role", "FRONT_MATTER")
                );
            }
            index = frontMatter.nextIndex();
        }

        while (index < lines.size()) {
            Line sourceLine = lines.get(index);
            String line = sourceLine.text();
            if (line.isBlank()) {
                index++;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                elements.headingFromArtifact(
                        level,
                        sourceLine.startOffset() + heading.start(2),
                        sourceLine.startOffset() + heading.end(2),
                        Map.of("level", Integer.toString(level))
                );
                index++;
                continue;
            }

            if (line.stripLeading().startsWith("```")) {
                Block block = fencedCode(lines, index, source);
                String language = line.stripLeading().substring(3).strip();
                if (!block.range().isEmpty()) {
                    elements.addFromArtifact(
                            ElementType.CODE,
                            block.range().startOffset(),
                            block.range().endOffset(),
                            language.isEmpty() ? Map.of() : Map.of("language", language)
                    );
                }
                index = block.nextIndex();
                continue;
            }

            ElementType type = classify(line, lines, index);
            Block block = collectBlock(lines, index, type, source);
            if (!block.range().isEmpty()) {
                elements.addFromArtifact(
                        type,
                        block.range().startOffset(),
                        block.range().endOffset(),
                        Map.of()
                );
            }
            index = block.nextIndex();
        }
        return elements.parsedDocument(
                id(),
                VERSION,
                Map.of(
                        "charset", "UTF-8",
                        "artifactContract", NormalizedTextSource.CONTRACT
                )
        );
    }

    /**
     * 将文档开头完整闭合的 YAML Front Matter 作为带角色的结构元素输出。
     *
     * <p>Parser 仅识别边界，不决定是否参与索引；Cleaner 的空间配置负责
     * KEEP、REMOVE 或 METADATA_ONLY。未闭合的块仍按普通 Markdown 正文解析，
     * 避免因格式错误静默丢失后续内容。</p>
     */
    private static FrontMatter frontMatter(
            List<Line> lines,
            NormalizedTextSource source
    ) {
        if (lines.isEmpty() || !"---".equals(lines.getFirst().text().strip())) {
            return null;
        }
        for (int index = 1; index < lines.size(); index++) {
            if ("---".equals(lines.get(index).text().strip())) {
                int start = index == 1
                        ? lines.getFirst().endOffset()
                        : lines.get(1).startOffset();
                int end = index == 1
                        ? start
                        : lines.get(index - 1).endOffset();
                return new FrontMatter(source.strip(start, end), index + 1);
            }
        }
        return null;
    }

    /**
     * 未闭合的 fenced code block 保留到文件尾，避免静默丢失正文。
     */
    private static Block fencedCode(
            List<Line> lines,
            int start,
            NormalizedTextSource source
    ) {
        int index = start + 1;
        while (index < lines.size()
                && !lines.get(index).text().stripLeading().startsWith("```")) {
            index++;
        }
        int contentStart = start + 1 < lines.size()
                ? lines.get(start + 1).startOffset()
                : lines.get(start).endOffset();
        int contentEnd = index > start + 1
                ? lines.get(index - 1).endOffset()
                : contentStart;
        if (index < lines.size()) {
            index++;
        }
        return new Block(source.strip(contentStart, contentEnd), index);
    }

    private static ElementType classify(String line, List<Line> lines, int index) {
        if (LIST_ITEM.matcher(line).matches()) {
            return ElementType.LIST;
        }
        if (looksLikeTableRow(line)
                && index + 1 < lines.size()
                && looksLikeTableSeparator(lines.get(index + 1).text())) {
            return ElementType.TABLE;
        }
        return ElementType.PARAGRAPH;
    }

    /**
     * 收集连续结构块，并在空行、标题或代码围栏处停止。
     */
    private static Block collectBlock(
            List<Line> lines,
            int start,
            ElementType type,
            NormalizedTextSource source
    ) {
        int index = start;
        int end = lines.get(start).endOffset();
        while (index < lines.size()) {
            String line = lines.get(index).text();
            if (line.isBlank() || HEADING.matcher(line).matches()
                    || line.stripLeading().startsWith("```")) {
                break;
            }
            if (index > start && type == ElementType.LIST && !LIST_ITEM.matcher(line).matches()) {
                break;
            }
            if (index > start && type == ElementType.TABLE && !looksLikeTableRow(line)) {
                break;
            }
            end = lines.get(index).endOffset();
            index++;
        }
        return new Block(source.strip(lines.get(start).startOffset(), end), index);
    }

    private static boolean looksLikeTableRow(String line) {
        return line.indexOf('|') >= 0;
    }

    private static boolean looksLikeTableSeparator(String line) {
        String normalized = line.replace("|", "").replace(":", "")
                .replace("-", "").replace(" ", "");
        return normalized.isEmpty() && line.indexOf('-') >= 0;
    }

    /** 表示扫描得到的连续文本范围。 */
    private record Block(TextRange range, int nextIndex) {
    }

    /** 表示已完整闭合的前置元数据块及其后续起点。 */
    private record FrontMatter(TextRange range, int nextIndex) {
    }
}
