# ADR-004：使用 OIDC/Keycloak 认证，业务库只管理租户授权

## 状态

已接受。

## 决策

Control Plane 作为 OAuth2 Resource Server 校验 JWT。Keycloak 是本地开发和默认部署的身份提供者，但 API 只依赖标准 OIDC。用户密码、MFA 和企业身份联邦不进入知识库数据库。

JWT 必须包含 `tenant_id` 和 `sub`；角色来自 `realm_access.roles`，部门来自 `departments`。数据库保存租户内主体镜像、知识空间 ACL 和审计关系。

## 后果

客户端请求中的普通 `tenantId` 字段不可信且不参与授权。切换其他 OIDC Provider 时只需要调整 Claim Mapper，不修改 Runtime。

