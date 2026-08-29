package dev.infinityknowledge.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 集中维护摄取链路中的确定性标识与规范化规则。
 *
 * <p>这些算法已经参与持久化文档和修订标识，任何字段顺序、分隔符或
 * 规范化规则的变化都必须视为数据迁移，而不是普通重构。</p>
 */
public final class IngestionIdentity {

    private static final String REVISION_FIELD_SEPARATOR = "\u001F";

    private IngestionIdentity() {
    }

    /**
     * 按租户、空间和外部来源身份生成稳定文档标识。
     *
     * @param tenantId 租户标识
     * @param spaceId 知识空间标识
     * @param source 外部来源描述
     * @return 可重复生成的文档标识
     */
    public static DocumentId documentId(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        String identity = String.join(
                ":",
                tenantId.value(),
                spaceId.value(),
                source.connectorId(),
                source.externalId()
        );
        return new DocumentId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 为完整且不可变的修订处理契约生成稳定标识。
     *
     * @param documentId 文档标识
     * @param contentHash 规范化内容指纹
     * @param mediaType 规范化媒体类型
     * @param language 规范化语言标签
     * @param processorVersion 解析和切分处理契约
     * @return 可重复生成的修订标识
     */
    public static UUID revisionId(
            DocumentId documentId,
            String contentHash,
            String mediaType,
            String language,
            String processorVersion
    ) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        String identity = String.join(
                REVISION_FIELD_SEPARATOR,
                documentId.value().toString(),
                contentHash,
                mediaType,
                language,
                processorVersion
        );
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将 BCP 47 语言标签转换为 JDK 的规范形式。
     *
     * @param language 原始语言标签
     * @return 规范化语言标签
     */
    public static String normalizeLanguageTag(String language) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        try {
            return new Locale.Builder()
                    .setLanguageTag(language.strip())
                    .build()
                    .toLanguageTag();
        } catch (IllformedLocaleException invalidLanguage) {
            throw new IllegalArgumentException(
                    "language must be a well-formed BCP 47 tag",
                    invalidLanguage
            );
        }
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 指纹。
     *
     * @param content 文本内容
     * @return 小写十六进制指纹
     */
    public static String sha256(String content) {
        Objects.requireNonNull(content, "content must not be null");
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算原始字节的 SHA-256 指纹。
     *
     * @param content 原始字节
     * @return 小写十六进制指纹
     */
    public static String sha256(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

}
