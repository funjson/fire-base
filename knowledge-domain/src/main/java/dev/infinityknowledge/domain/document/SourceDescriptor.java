package dev.infinityknowledge.domain.document;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.Map;
import java.util.Objects;

/**
 * 描述文档在外部知识源中的稳定位置和同步属性。
 *
 * @param connectorId 连接器实例标识
 * @param type 来源类型
 * @param externalId 外部系统稳定标识
 * @param uri 可供审计或跳转的来源 URI
 * @param attributes 非敏感来源属性
 */
public record SourceDescriptor(
        String connectorId,
        SourceType type,
        String externalId,
        String uri,
        Map<String, String> attributes
) {

    /**
     * 校验来源定位并复制属性。
     */
    public SourceDescriptor {
        connectorId = DomainChecks.requiredText(connectorId, "connectorId", 128);
        Objects.requireNonNull(type, "sourceType must not be null");
        externalId = DomainChecks.requiredText(externalId, "externalId", 512);
        uri = DomainChecks.requiredText(uri, "sourceUri", 2048);
        attributes = Map.copyOf(
                Objects.requireNonNull(attributes, "source attributes must not be null")
        );
    }
}

