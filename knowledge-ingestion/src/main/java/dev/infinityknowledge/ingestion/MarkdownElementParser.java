package dev.infinityknowledge.ingestion;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Markdown 解析为保留标题路径的结构元素。
 *
 * <p>该解析器刻意不把 Markdown 转成 HTML，避免丢失原始代码、表格与 Obsidian 语法。
 * 它负责稳定的结构提取，而不负责 Front Matter 的业务解释。</p>
 */
public final class MarkdownElementParser {

    /** 当前解析器语义版本。 */
    public static final String VERSION = "markdown-structure-v1";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+.+$");

    /**
     * 解析一个 Markdown 修订。
     *
     * @param revisionId 修订标识
     * @param markdown 原始 Markdown
     * @return 按原文顺序排列的结构元素
     */
    public List<KnowledgeElement> parse(UUID revisionId, String markdown) {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(markdown, "markdown must not be null");

        List<String> lines = markdown.lines().toList();
        List<KnowledgeElement> elements = new ArrayList<>();
        Deque<String> headings = new ArrayDeque<>();
        int ordinal = 0;
        int index = frontMatterEnd(lines);

        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.isBlank()) {
                index++;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String title = heading.group(2).strip();
                trimHeadings(headings, level - 1);
                headings.addLast(title);
                elements.add(element(
                        revisionId,
                        ordinal++,
                        ElementType.HEADING,
                        List.copyOf(headings),
                        title,
                        Map.of("level", Integer.toString(level))
                ));
                index++;
                continue;
            }

            if (line.stripLeading().startsWith("```")) {
                Block block = fencedCode(lines, index);
                String language = line.stripLeading().substring(3).strip();
                elements.add(element(
                        revisionId,
                        ordinal++,
                        ElementType.CODE,
                        List.copyOf(headings),
                        block.content(),
                        language.isEmpty() ? Map.of() : Map.of("language", language)
                ));
                index = block.nextIndex();
                continue;
            }

            ElementType type = classify(line, lines, index);
            Block block = collectBlock(lines, index, type);
            elements.add(element(
                    revisionId,
                    ordinal++,
                    type,
                    List.copyOf(headings),
                    block.content(),
                    Map.of()
            ));
            index = block.nextIndex();
        }
        return List.copyOf(elements);
    }

    /**
     * 跳过文档开头的 YAML Front Matter。
     */
    private static int frontMatterEnd(List<String> lines) {
        if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) {
            return 0;
        }
        for (int index = 1; index < lines.size(); index++) {
            if ("---".equals(lines.get(index).strip())) {
                return index + 1;
            }
        }
        return 0;
    }

    /**
     * 调整标题栈，使其匹配即将进入的标题层级。
     */
    private static void trimHeadings(Deque<String> headings, int parentCount) {
        while (headings.size() > parentCount) {
            headings.removeLast();
        }
    }

    /**
     * 读取完整 fenced code block；未闭合代码块一直保留至文件尾。
     */
    private static Block fencedCode(List<String> lines, int start) {
        StringBuilder content = new StringBuilder();
        int index = start + 1;
        while (index < lines.size() && !lines.get(index).stripLeading().startsWith("```")) {
            appendLine(content, lines.get(index));
            index++;
        }
        if (index < lines.size()) {
            index++;
        }
        return new Block(content.toString(), index);
    }

    /**
     * 根据起始行识别结构类型。
     */
    private static ElementType classify(String line, List<String> lines, int index) {
        if (LIST_ITEM.matcher(line).matches()) {
            return ElementType.LIST;
        }
        if (looksLikeTableRow(line)
                && index + 1 < lines.size()
                && looksLikeTableSeparator(lines.get(index + 1))) {
            return ElementType.TABLE;
        }
        return ElementType.PARAGRAPH;
    }

    /**
     * 收集同类型的连续行，并在下一个结构边界停止。
     */
    private static Block collectBlock(List<String> lines, int start, ElementType type) {
        StringBuilder content = new StringBuilder();
        int index = start;
        while (index < lines.size()) {
            String line = lines.get(index);
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
            appendLine(content, line);
            index++;
        }
        return new Block(content.toString(), index);
    }

    /**
     * 判断一行是否可能是 Markdown 表格行。
     */
    private static boolean looksLikeTableRow(String line) {
        return line.indexOf('|') >= 0;
    }

    /**
     * 判断一行是否为 Markdown 表头分隔线。
     */
    private static boolean looksLikeTableSeparator(String line) {
        String normalized = line.replace("|", "").replace(":", "")
                .replace("-", "").replace(" ", "");
        return normalized.isEmpty() && line.indexOf('-') >= 0;
    }

    /**
     * 追加一行并保留块内换行。
     */
    private static void appendLine(StringBuilder target, String line) {
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(line);
    }

    /**
     * 创建具有确定性标识的元素。
     */
    private static KnowledgeElement element(
            UUID revisionId,
            int ordinal,
            ElementType type,
            List<String> sectionPath,
            String content,
            Map<String, String> attributes
    ) {
        String key = revisionId + ":" + ordinal + ":" + type + ":" + content;
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return new KnowledgeElement(
                id,
                revisionId,
                null,
                type,
                ordinal,
                sectionPath,
                content,
                new LinkedHashMap<>(attributes)
        );
    }

    /**
     * 表示扫描得到的连续文本块。
     */
    private record Block(String content, int nextIndex) {
    }
}
