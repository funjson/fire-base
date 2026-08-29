package dev.infinityknowledge.spi.access;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;
import java.util.Set;

/**
 * Policy Engine 编译后的不可变授权范围。
 *
 * <p>{@link Mode#ALL} 表示可读取 {@code spaceIds} 内的全部文档；
 * {@link Mode#ONLY} 表示只能读取 {@code documentIds} 白名单；
 * {@link Mode#DENY_ALL} 表示明确拒绝。租户边界始终存在，任何模式都不能跨租户。</p>
 *
 * @param tenantId 唯一允许访问的租户
 * @param mode 文档选择语义
 * @param spaceIds 允许访问的知识空间
 * @param documentIds ONLY 模式下的文档白名单
 */
public record AccessScope(
        TenantId tenantId,
        Mode mode,
        Set<KnowledgeSpaceId> spaceIds,
        Set<String> documentIds
) {

    /**
     * 显式的授权选择模式。
     */
    public enum Mode {
        /**
         * 允许所列空间中的全部文档。
         */
        ALL,

        /**
         * 仅允许所列文档。
         */
        ONLY,

        /**
         * 不允许读取任何文档。
         */
        DENY_ALL
    }

    /**
     * 复制授权集合并校验模式不变量，防止空集合产生歧义。
     */
    public AccessScope {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        spaceIds = Set.copyOf(Objects.requireNonNull(spaceIds, "spaceIds must not be null"));
        documentIds = Set.copyOf(
                Objects.requireNonNull(documentIds, "documentIds must not be null")
        );
        if (documentIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("documentIds must not contain blank values");
        }
        switch (mode) {
            case ALL -> {
                requireSpaces(spaceIds);
                if (!documentIds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "ALL access scope must not contain documentIds"
                    );
                }
            }
            case ONLY -> {
                requireSpaces(spaceIds);
                if (documentIds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "ONLY access scope must contain at least one document"
                    );
                }
            }
            case DENY_ALL -> {
                if (!spaceIds.isEmpty() || !documentIds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "DENY_ALL access scope must not contain spaces or documents"
                    );
                }
            }
        }
    }

    /**
     * 保留原有调用方式：空文档集合表示空间内全部文档，非空集合表示文档白名单。
     */
    public AccessScope(
            TenantId tenantId,
            Set<KnowledgeSpaceId> spaceIds,
            Set<String> documentIds
    ) {
        this(
                tenantId,
                documentIds == null || documentIds.isEmpty() ? Mode.ALL : Mode.ONLY,
                spaceIds,
                documentIds
        );
    }

    /**
     * 创建所列空间内全部文档的授权范围。
     */
    public static AccessScope all(TenantId tenantId, Set<KnowledgeSpaceId> spaceIds) {
        return new AccessScope(tenantId, Mode.ALL, spaceIds, Set.of());
    }

    /**
     * 创建所列空间内指定文档的授权范围。
     */
    public static AccessScope only(
            TenantId tenantId,
            Set<KnowledgeSpaceId> spaceIds,
            Set<String> documentIds
    ) {
        return new AccessScope(tenantId, Mode.ONLY, spaceIds, documentIds);
    }

    /**
     * 创建仍受租户绑定的明确拒绝范围。
     */
    public static AccessScope denyAll(TenantId tenantId) {
        return new AccessScope(tenantId, Mode.DENY_ALL, Set.of(), Set.of());
    }

    /**
     * 是否明确拒绝所有知识读取。
     */
    public boolean deniesAll() {
        return mode == Mode.DENY_ALL;
    }

    /**
     * 是否限制到文档白名单。
     */
    public boolean restrictsDocuments() {
        return mode == Mode.ONLY;
    }

    /**
     * 判断空间是否处于授权范围。
     */
    public boolean allowsSpace(KnowledgeSpaceId spaceId) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        return mode != Mode.DENY_ALL && spaceIds.contains(spaceId);
    }

    /**
     * 判断文档是否处于授权范围。
     */
    public boolean allowsDocument(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        return switch (mode) {
            case ALL -> true;
            case ONLY -> documentIds.contains(documentId);
            case DENY_ALL -> false;
        };
    }

    /**
     * 将已授权范围收窄到一个 Space，同时保持文档白名单语义不变。
     *
     * <p>该方法只能收窄既有授权，不能用来选择尚未授权的 Space。</p>
     *
     * @param spaceId 已在当前范围内的 Space
     * @return 仅包含目标 Space 的授权范围
     */
    public AccessScope forSpace(KnowledgeSpaceId spaceId) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        if (!allowsSpace(spaceId)) {
            throw new IllegalArgumentException("spaceId is outside the authorized scope");
        }
        return new AccessScope(tenantId, mode, Set.of(spaceId), documentIds);
    }

    private static void requireSpaces(Set<KnowledgeSpaceId> spaceIds) {
        if (spaceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "ALL and ONLY access scopes must contain at least one space"
            );
        }
    }
}
