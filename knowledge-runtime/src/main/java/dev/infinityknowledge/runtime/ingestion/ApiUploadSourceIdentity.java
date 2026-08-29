package dev.infinityknowledge.runtime.ingestion;

import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * 生成 API 上传来源的稳定业务身份和可审计逻辑 URI。
 *
 * <p>该类型不解析 MinIO Bucket 或 Key。逻辑 URI 只用于审计和定位，真正下载必须
 * 继续经过租户、Space 与 ACL 校验的 Source API。</p>
 */
public final class ApiUploadSourceIdentity {

    private ApiUploadSourceIdentity() {
    }

    /** 返回目标 Space 的虚拟上传连接器标识。 */
    public static String connectorId(KnowledgeSpaceId spaceId) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        return "api-upload:" + spaceId.value();
    }

    /** 创建不含对象存储物理位置的来源描述。 */
    public static SourceDescriptor descriptor(
            KnowledgeSpaceId spaceId,
            String externalId,
            String fileName,
            String mediaType
    ) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(externalId, "externalId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        return new SourceDescriptor(
                connectorId(spaceId),
                SourceType.UPLOAD,
                externalId,
                logicalUri(spaceId, externalId),
                Map.of("originalFileName", fileName, "mediaType", mediaType)
        );
    }

    /**
     * 生成可逆且不会被斜杠、中文或保留字符改变层级的逻辑来源 URI。
     */
    public static String logicalUri(KnowledgeSpaceId spaceId, String externalId) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(externalId, "externalId must not be null");
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "upload://" + encoder.encodeToString(
                spaceId.value().getBytes(StandardCharsets.UTF_8)
        ) + "/" + encoder.encodeToString(externalId.getBytes(StandardCharsets.UTF_8));
    }
}
