package dev.infinityknowledge.domain.identity;

import java.util.Objects;
import java.util.Set;

/**
 * 保存由认证层构造的租户主体及其角色、部门声明。
 *
 * <p>普通 API 请求不得直接创建或覆盖该对象；Control Plane 必须从已验证 JWT
 * 或受信服务凭据生成它。</p>
 *
 * @param tenantId 当前租户
 * @param principalId 当前用户或服务主体
 * @param roleIds 当前租户内角色
 * @param departmentIds 当前租户内部门
 * @param systemPrincipal 是否为显式后台系统主体
 */
public record PrincipalContext(
        TenantId tenantId,
        PrincipalId principalId,
        Set<String> roleIds,
        Set<String> departmentIds,
        boolean systemPrincipal
) {

    /**
     * 校验主体边界并复制权限集合，避免调用方在查询期间修改权限。
     */
    public PrincipalContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(principalId, "principalId must not be null");
        roleIds = Set.copyOf(Objects.requireNonNull(roleIds, "roleIds must not be null"));
        departmentIds = Set.copyOf(
                Objects.requireNonNull(departmentIds, "departmentIds must not be null")
        );
    }
}

