package dev.infinityknowledge.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在标题边界上构建检索 Chunk，并对超大元素进行受控拆分。
 */
public final class HeadingAwareChunker {

    /**
     * Included in immutable index-generation fingerprints.
     */
    public static final String VERSION = "heading-aware-v1";

    private final int targetCharacters;
    private final int maximumCharacters;

    /**
     * 创建标题感知切分器。
     *
     * @param targetCharacters 常规 Chunk 目标字符数
     * @param maximumCharacters 单个 Chunk 最大字符数
     */
    public HeadingAwareChunker(int targetCharacters, int maximumCharacters) {
        if (targetCharacters < 128) {
            throw new IllegalArgumentException("targetCharacters must be at least 128");
        }
        if (maximumCharacters < targetCharacters) {
            throw new IllegalArgumentException("maximumCharacters must not be less than targetCharacters");
        }
        this.targetCharacters = targetCharacters;
        this.maximumCharacters = maximumCharacters;
    }

    /**
     * 将结构元素切分为可检索单元。
     *
     * @param tenantId 租户
     * @param spaceId 知识空间
     * @param documentId 文档
     * @param revisionId 修订
     * @param elements 结构元素
     * @return 确定性排序的 Chunk
     */
    public List<KnowledgeChunk> chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            List<KnowledgeElement> elements
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(elements, "elements must not be null");

        List<ChunkDraft> drafts = new ArrayList<>();
        ChunkDraft current = null;
        for (KnowledgeElement element : elements) {
            if (!revisionId.equals(element.revisionId())) {
                throw new IllegalArgumentException("element revision does not match requested revision");
            }
            if (element.type() == ElementType.HEADING) {
                if (current != null && !current.elementIds().isEmpty()) {
                    drafts.add(current);
                }
                current = new ChunkDraft(element.sectionPath());
                current.add(element);
                continue;
            }
            if (current == null) {
                current = new ChunkDraft(element.sectionPath());
            }
            if (current.lengthAfter(element) > targetCharacters && !current.elementIds().isEmpty()) {
                drafts.add(current);
                current = new ChunkDraft(element.sectionPath());
            }
            if (element.content().length() > maximumCharacters) {
                if (!current.elementIds().isEmpty()) {
                    drafts.add(current);
                    current = new ChunkDraft(element.sectionPath());
                }
                for (String part : splitOversized(element.content())) {
                    ChunkDraft oversized = new ChunkDraft(element.sectionPath());
                    oversized.add(element.id(), part);
                    drafts.add(oversized);
                }
                current = null;
            } else {
                current.add(element);
            }
        }
        if (current != null && !current.elementIds().isEmpty()) {
            drafts.add(current);
        }
        return materialize(tenantId, spaceId, documentId, revisionId, drafts);
    }

    /**
     * 在换行、句号或最大长度处拆分超大元素。
     */
    private List<String> splitOversized(String content) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int hardEnd = Math.min(start + maximumCharacters, content.length());
            int end = boundary(content, start, hardEnd);
            parts.add(content.substring(start, end).strip());
            start = end;
            while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
        }
        return parts;
    }

    /**
     * 从最大位置向前寻找自然文本边界。
     */
    private static int boundary(String content, int start, int hardEnd) {
        if (hardEnd == content.length()) {
            return hardEnd;
        }
        int minimum = start + Math.max(64, (hardEnd - start) / 2);
        for (int index = hardEnd; index > minimum; index--) {
            char character = content.charAt(index - 1);
            if (character == '\n' || character == '。' || character == '.'
                    || character == '！' || character == '？') {
                return index;
            }
        }
        return hardEnd;
    }

    /**
     * 将草稿转换成领域 Chunk。
     */
    private static List<KnowledgeChunk> materialize(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            List<ChunkDraft> drafts
    ) {
        List<KnowledgeChunk> chunks = new ArrayList<>(drafts.size());
        for (int ordinal = 0; ordinal < drafts.size(); ordinal++) {
            ChunkDraft draft = drafts.get(ordinal);
            String content = draft.content().toString();
            String contentHash = sha256(content);
            UUID chunkId = UUID.nameUUIDFromBytes(
                    (revisionId + ":" + ordinal + ":" + contentHash).getBytes(StandardCharsets.UTF_8)
            );
            chunks.add(new KnowledgeChunk(
                    chunkId,
                    tenantId,
                    spaceId,
                    documentId,
                    revisionId,
                    draft.elementIds(),
                    ordinal,
                    draft.sectionPath(),
                    content,
                    contentHash,
                    Map.of("chunker", "heading-aware-v1")
            ));
        }
        return List.copyOf(chunks);
    }

    /**
     * 计算正文 SHA-256。
     */
    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 切分过程中使用的可变草稿，仅在单次调用内存活。
     */
    private static final class ChunkDraft {
        private final List<String> sectionPath;
        private final List<UUID> elementIds = new ArrayList<>();
        private final StringBuilder content = new StringBuilder();

        /**
         * 创建属于某章节路径的草稿。
         */
        private ChunkDraft(List<String> sectionPath) {
            this.sectionPath = List.copyOf(sectionPath);
        }

        /**
         * 计算追加元素后的字符数。
         */
        private int lengthAfter(KnowledgeElement element) {
            return content.length() + (content.isEmpty() ? 0 : 2) + element.content().length();
        }

        /**
         * 追加完整元素。
         */
        private void add(KnowledgeElement element) {
            add(element.id(), element.content());
        }

        /**
         * 追加元素的一段正文。
         */
        private void add(UUID elementId, String text) {
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(text);
            if (!elementIds.contains(elementId)) {
                elementIds.add(elementId);
            }
        }

        /**
         * 返回章节路径。
         */
        private List<String> sectionPath() {
            return sectionPath;
        }

        /**
         * 返回来源元素标识。
         */
        private List<UUID> elementIds() {
            return List.copyOf(elementIds);
        }

        /**
         * 返回正文构建器。
         */
        private StringBuilder content() {
            return content;
        }
    }
}
