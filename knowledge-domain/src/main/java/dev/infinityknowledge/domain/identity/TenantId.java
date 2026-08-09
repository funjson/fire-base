package dev.infinityknowledge.domain.identity;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 表示所有知识数据必须携带的稳定租户标识。
 *
 * @param value 租户标识文本
 */
public record TenantId(String value) {

    /**
     * 规范化租户标识，防止空标识绕过租户过滤。
     */
    public TenantId {
        value = DomainChecks.requiredText(value, "tenantId", 64);
    }
}

