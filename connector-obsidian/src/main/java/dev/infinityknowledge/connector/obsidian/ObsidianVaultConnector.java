package dev.infinityknowledge.connector.obsidian;

import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorBatch;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.SourceConnector;
import dev.infinityknowledge.spi.connector.SourceRecord;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 将本地 Obsidian Vault 读取为可对账的 Markdown 知识源快照。
 *
 * <p>连接器不跟随符号链接，并忽略 `.obsidian` 等配置目录。每轮同步按相对路径排序分页；
 * Runtime 依据 externalId 与内容哈希幂等 upsert，并在完整快照成功后对账删除；
 * 路径移动按旧文档归档与新文档创建处理。</p>
 */
public final class ObsidianVaultConnector implements SourceConnector {
    private static final Pattern WIKI_LINK = Pattern.compile(
            "(?<!\\!)\\[\\[([^\\]|#]+)(?:#[^\\]|]+)?(?:\\|[^\\]]+)?]]"
    );
    private static final Pattern EMBEDDED_LINK = Pattern.compile(
            "!\\[\\[([^\\]|#]+)(?:#[^\\]|]+)?(?:\\|[^\\]]+)?]]"
    );
    private static final Pattern MARKDOWN_TAG = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_/-]+)"
    );
    private final ObsidianConnectorConfig config;
    private final List<Path> snapshotFiles;

    /**
     * 创建只读 Vault 连接器。
     *
     * @param config Vault 配置
     */
    public ObsidianVaultConnector(ObsidianConnectorConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.snapshotFiles = markdownFiles();
    }

    /**
     * 返回配置中的稳定连接器标识。
     *
     * @return 连接器标识
     */
    @Override
    public String connectorId() {
        return config.connectorId();
    }

    /**
     * 按相对路径分页读取当前 Vault 快照。
     *
     * @param tenantId 目标租户
     * @param spaceId 目标知识空间
     * @param cursor 当前快照偏移游标
     * @param limit 最大 Markdown 数量
     * @return 当前页记录和下一偏移
     */
    @Override
    public ConnectorBatch pull(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ConnectorCursor cursor,
            int limit
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(cursor, "cursor must not be null");
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        int offset = parseOffset(cursor);
        if (offset > snapshotFiles.size()) {
            throw new IllegalArgumentException("connector cursor is outside current vault snapshot");
        }
        int end = Math.min(snapshotFiles.size(), offset + limit);
        List<SourceRecord> records = snapshotFiles.subList(offset, end).stream()
                .map(this::readRecord)
                .toList();
        boolean hasMore = end < snapshotFiles.size();
        ConnectorCursor next = hasMore
                ? new ConnectorCursor(Map.of("offset", Integer.toString(end)))
                : ConnectorCursor.initial();
        return new ConnectorBatch(records, next, hasMore);
    }

    /**
     * 解析非负分页偏移。
     *
     * @param cursor 外部游标
     * @return 文件偏移
     */
    private int parseOffset(ConnectorCursor cursor) {
        String raw = cursor.values().getOrDefault("offset", "0");
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) {
                throw new IllegalArgumentException("connector cursor offset must be non-negative");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("connector cursor offset is invalid", invalid);
        }
    }

    /**
     * 扫描并稳定排序所有未被忽略的 Markdown 文件。
     *
     * @return Vault 内 Markdown 文件
     */
    private List<Path> markdownFiles() {
        if (!Files.isDirectory(config.vaultRoot())) {
            throw new IllegalStateException("Obsidian vault root is not a directory");
        }
        try (Stream<Path> paths = Files.walk(config.vaultRoot())) {
            return paths.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isMarkdown)
                    .filter(path -> !isIgnored(path))
                    .sorted(Comparator.comparing(this::relativePath))
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to scan Obsidian vault", failure);
        }
    }

    /**
     * 判断文件扩展名是否为 Markdown。
     *
     * @param path 文件路径
     * @return 是否为 Markdown
     */
    private boolean isMarkdown(Path path) {
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md");
    }

    /**
     * 判断相对目录是否包含配置指定的忽略目录。
     *
     * @param path 文件路径
     * @return 是否忽略
     */
    private boolean isIgnored(Path path) {
        Path relative = config.vaultRoot().relativize(path.toAbsolutePath().normalize());
        for (Path component : relative) {
            if (config.ignoredDirectoryNames().contains(component.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取单个 Markdown，并提取 Frontmatter、WikiLink、标签与附件引用。
     *
     * @param path Markdown 路径
     * @return 来源记录
     */
    private SourceRecord readRecord(Path path) {
        try {
            if (Files.isSymbolicLink(path)) {
                throw new SecurityException("Obsidian symbolic links are not readable sources");
            }
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(config.vaultRoot())) {
                throw new SecurityException("Obsidian file escaped configured vault root");
            }
            long size = Files.size(realPath);
            if (size > config.maxFileBytes()) {
                throw new IllegalStateException(
                        "Obsidian markdown exceeds configured size limit: " + relativePath(path)
                );
            }
            String content = Files.readString(realPath, StandardCharsets.UTF_8);
            ParsedMarkdown parsed = parseMarkdown(content, path);
            String relativePath = relativePath(path);
            Map<String, String> attributes = Map.of(
                    "vault", config.vaultName(),
                    "relativePath", relativePath
            );
            SourceDescriptor source = new SourceDescriptor(
                    config.connectorId(),
                    SourceType.OBSIDIAN,
                    relativePath,
                    obsidianUri(relativePath),
                    attributes
            );
            Map<String, String> metadata = new HashMap<>(parsed.frontmatter());
            metadata.put("obsidian.wikilinks", String.join("\u001F", parsed.wikiLinks()));
            metadata.put("obsidian.embeds", String.join("\u001F", parsed.embeddedLinks()));
            metadata.put("obsidian.tags", String.join("\u001F", parsed.tags()));
            FileTime modified = Files.getLastModifiedTime(realPath);
            return new SourceRecord(
                    source,
                    parsed.title(),
                    "text/markdown",
                    content,
                    sha256(content),
                    metadata,
                    modified.toInstant(),
                    false
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to read Obsidian markdown " + relativePath(path),
                    failure
            );
        }
    }

    /**
     * 解析 Markdown 中 Obsidian 特有的结构元数据。
     *
     * @param content Markdown 正文
     * @param path 文件路径
     * @return 解析结果
     */
    private ParsedMarkdown parseMarkdown(String content, Path path) {
        List<String> lines = content.lines().toList();
        Map<String, String> frontmatter = parseFrontmatter(lines);
        String title = frontmatter.get("title");
        if (title == null || title.isBlank()) {
            title = firstHeading(lines);
        }
        if (title == null || title.isBlank()) {
            String fileName = path.getFileName().toString();
            title = fileName.substring(0, fileName.length() - 3);
        }
        Set<String> wikiLinks = matches(WIKI_LINK, content);
        Set<String> embeds = matches(EMBEDDED_LINK, content);
        Set<String> tags = matches(MARKDOWN_TAG, content);
        tags.addAll(frontmatterTags(frontmatter.get("tags")));
        return new ParsedMarkdown(
                title.strip(),
                frontmatter,
                wikiLinks,
                embeds,
                tags
        );
    }

    /**
     * 解析文件开头的简单 YAML Frontmatter 标量和行内数组。
     *
     * @param lines Markdown 行
     * @return Frontmatter 键值
     */
    private Map<String, String> parseFrontmatter(List<String> lines) {
        if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if ("---".equals(line.strip())) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                String key = line.substring(0, separator).strip();
                String value = line.substring(separator + 1).strip();
                if (!key.isEmpty() && !value.isEmpty()) {
                    values.put(key, unquote(value));
                }
            }
        }
        return Map.copyOf(values);
    }

    /**
     * 返回第一条一级到六级 Markdown 标题。
     *
     * @param lines Markdown 行
     * @return 标题或 {@code null}
     */
    private String firstHeading(List<String> lines) {
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.matches("^#{1,6}\\s+.+$")) {
                return stripped.replaceFirst("^#{1,6}\\s+", "").strip();
            }
        }
        return null;
    }

    /**
     * 收集正则表达式的第一个捕获组，并保持首次出现顺序。
     *
     * @param pattern 正则表达式
     * @param content Markdown 正文
     * @return 去重匹配值
     */
    private Set<String> matches(Pattern pattern, String content) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            values.add(matcher.group(1).strip());
        }
        return values;
    }

    /**
     * 解析 Frontmatter 中的逗号分隔或行内数组标签。
     *
     * @param raw 标签文本
     * @return 标签集合
     */
    private Set<String> frontmatterTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        String normalized = raw.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String value : normalized.split(",")) {
            String tag = unquote(value.strip()).replaceFirst("^#", "");
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /**
     * 移除成对的单引号或双引号。
     *
     * @param value 原始值
     * @return 去引号值
     */
    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 返回统一使用正斜杠的 Vault 相对路径。
     *
     * @param path 文件路径
     * @return 相对路径
     */
    private String relativePath(Path path) {
        return config.vaultRoot()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    /**
     * 构造不包含本地绝对路径的 Obsidian 跳转 URI。
     *
     * @param relativePath Vault 相对路径
     * @return Obsidian URI
     */
    private String obsidianUri(String relativePath) {
        return "obsidian://open?vault="
                + URLEncoder.encode(config.vaultName(), StandardCharsets.UTF_8)
                + "&file="
                + URLEncoder.encode(relativePath, StandardCharsets.UTF_8);
    }

    /**
     * 计算文本内容指纹。
     *
     * @param content 文本内容
     * @return SHA-256 十六进制
     */
    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 保存一次 Markdown 解析得到的结构元数据。
     *
     * @param title 标题
     * @param frontmatter Frontmatter
     * @param wikiLinks WikiLink
     * @param embeddedLinks 嵌入附件
     * @param tags 标签
     */
    private record ParsedMarkdown(
            String title,
            Map<String, String> frontmatter,
            Set<String> wikiLinks,
            Set<String> embeddedLinks,
            Set<String> tags
    ) {

        /**
         * 复制解析集合，防止后续扫描修改本次快照。
         */
        private ParsedMarkdown {
            title = Objects.requireNonNull(title, "title must not be null");
            frontmatter = Map.copyOf(frontmatter);
            wikiLinks = Set.copyOf(wikiLinks);
            embeddedLinks = Set.copyOf(embeddedLinks);
            tags = Set.copyOf(tags);
        }
    }
}
