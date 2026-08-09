package dev.infinityknowledge.domain.identity;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 表示经过认证的用户或服务主体标识。
 *
 * @param value 主体标识文本
 */
public record PrincipalId(String value) {

    /**
     * 规范化主体标识。
     */
    public PrincipalId {
        value = DomainChecks.requiredText(value, "principalId", 128);
    }
}

