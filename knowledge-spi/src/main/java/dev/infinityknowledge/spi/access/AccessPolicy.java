package dev.infinityknowledge.spi.access;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Set;

/**
 * 将已认证主体和请求范围编译为索引可执行的访问过滤器。
 */
@FunctionalInterface
public interface AccessPolicy {

    /**
     * 解析当前主体允许访问的知识范围。
     *
     * @param principal 已认证主体
     * @param requestedSpaces 调用方请求的空间，空集合表示全部可访问空间
     * @return 强制租户隔离且具有显式 ALL、ONLY 或 DENY_ALL 语义的访问范围
     */
    AccessScope resolve(PrincipalContext principal, Set<KnowledgeSpaceId> requestedSpaces);
}
