# Phase 1 API 验收说明

基础地址：

```text
API       http://localhost:8080
Keycloak  http://localhost:8180
Realm     infinity-knowledge
Audience  infinity-knowledge-api
```

除 `/actuator/health` 外，业务端点都要求 Bearer JWT。租户、用户、角色和部门
只从已验证 Token 读取，任何请求体都不能覆盖。

## 1. 获取本地 Token

管理员：

```powershell
$token = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8180/realms/infinity-knowledge/protocol/openid-connect/token' `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{
    client_id  = 'infinity-knowledge-cli'
    grant_type = 'password'
    username   = 'demo-admin'
    password   = 'demo-admin'
  }

$headers = @{
  Authorization  = "Bearer $($token.access_token)"
  'X-Request-Id' = [Guid]::NewGuid().ToString()
}
```

Reader 使用 `demo-reader / demo-reader`。本地 Client 会在 Access Token 中加入
`tenant_id=demo`、Realm Roles、部门和 `infinity-knowledge-api` Audience。
不包含该 Audience 的同 Realm Token 会被 Resource Server 拒绝。

`X-Request-Id` 必须是 UUID；缺失或非法值会被替换为新 UUID。所有响应都在
Header 返回规范化值；请求进入应用层后，检索响应、`ApiError`、Trace 和 MDC
复用该值。安全过滤链直接产生的 401/403 只保证 Header 契约。

## 2. 空间与 ACL

### 2.1 创建空间

需要 `knowledge-admin`：

```powershell
$body = @{
  spaceId = 'engineering'
  name    = 'Engineering Knowledge'
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/spaces' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

创建操作幂等，并在同一事务中创建空间、创建者 Admin ACL 和空间级
`api-upload:<spaceId>` Connector；成功响应为 HTTP 204，无响应体。

### 2.2 当前主体可访问空间

管理员和 Reader 都可以调用：

```powershell
Invoke-RestMethod `
  -Uri 'http://localhost:8080/api/v1/spaces/accessible' `
  -Headers $headers
```

返回当前租户、角色、用户和部门 ACL 可读取的活动空间。Reader 的检索页面只
使用此端点，不调用 `/api/v1/admin/spaces`。

### 2.3 管理空间 ACL

需要 `knowledge-admin`。支持的 `subjectType`：
`USER`、`ROLE`、`DEPARTMENT`、`TENANT`；支持的 `permission`：
`READ`、`WRITE`、`ADMIN`。

```powershell
$acl = @{
  subjectType = 'USER'
  subjectId   = '<reader-sub-from-token>'
  permission  = 'READ'
} | ConvertTo-Json

# 查看
Invoke-RestMethod `
  -Uri 'http://localhost:8080/api/v1/spaces/engineering/acl' `
  -Headers $headers

# 授权
Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/spaces/engineering/acl' `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $acl

# 撤销精确匹配的授权
Invoke-RestMethod `
  -Method Delete `
  -Uri 'http://localhost:8080/api/v1/spaces/engineering/acl' `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $acl
```

授权改变会立即影响新的 `AccessScope`。`DENY_ALL`、空间内全部文档和精确
文档白名单在运行时是不同的显式状态，不再使用空集合猜测权限语义。

## 3. Markdown 文档

### 3.1 写入或更新

需要 `knowledge-admin`：

```powershell
$body = @{
  spaceId    = 'engineering'
  externalId = 'login-troubleshooting.md'
  title      = '用户登录故障排查'
  sourceUri  = 'https://kb.example.invalid/login-troubleshooting'
  language   = 'zh-CN'
  authority  = 90
  content    = @'
# 用户中心

## Redis 超时

Redis 超时需要检查连接池和网络配置。
'@
  metadata = @{ domain = 'identity' }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/documents/markdown' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

响应：

```json
{
  "documentId": "96e8dc64-b86d-4c75-a484-46d7e16fd75c",
  "revisionId": "ec495719-02df-4c3d-bbd5-c926f7f64a61",
  "changed": true,
  "elementCount": 3,
  "chunkCount": 1,
  "vectorStatus": "QUEUED",
  "warnings": []
}
```

`sourceUri` 必须使用 `KNOWLEDGE_ALLOWED_SOURCE_SCHEMES` 白名单中的绝对 URI
协议；默认允许 `http`、`https` 和 `obsidian`。相同活动内容为幂等写入；
A→B→A 会重新激活历史 A 修订并重新排队所需投影。正文相同但标题、来源、
权威等级或元数据变化时复用原修订并返回 `changed=true`，以便重建外部投影；
完全相同的活动文档才返回 `changed=false`。

### 3.2 管理列表与 Chunk

需要 `knowledge-admin`：

```text
GET /api/v1/admin/documents?spaceId=engineering&status=ACTIVE&limit=50&offset=0
GET /api/v1/admin/documents/{documentId}/chunks
```

文档列表响应为：

```json
{
  "items": [],
  "limit": 50,
  "offset": 0,
  "total": 0
}
```

无过滤、仅 `spaceId`、仅 `status` 和两者同时存在均为合法组合。

### 3.3 投影状态、重试和空间重建

```text
GET  /api/v1/documents/{documentId}/projections
POST /api/v1/documents/{documentId}/projections/{projectionType}/retry
POST /api/v1/spaces/{spaceId}/projections/rebuild
```

空间重建只为当前活动修订重新排队已启用的 `KEYWORD`/`VECTOR` 外部通道，
不会重置正在运行的 Job。单文档状态和 retry 同样只作用于当前活动修订，历史
修订的 DEAD Job 不会被管理操作重新执行：

```json
{
  "jobs": 24,
  "projectionTypes": ["KEYWORD", "VECTOR"]
}
```

## 4. Agent RAG 查询

```powershell
$body = @{
  query    = 'Redis 超时'
  spaceIds = @('engineering')
  topK     = 5
  filters  = @{ language = 'zh-CN' }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/knowledge/query' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

当前 API 只接受 `language` 和 `sourceType` Filter。PostgreSQL/ES 执行这些
过滤；当前 Milvus 通道遇到 metadata Filter 会明确降级并返回 Warning，不会
静默忽略。响应使用稳定 HTTP DTO，不暴露 Java Value Object 的内部序列化：

```json
{
  "requestId": "fbbf9907-a75e-4bd0-948c-02790ffcaeba",
  "traceId": "0051871b-994f-4304-ae4f-c6f8b069d6f2",
  "tenantId": "demo",
  "evidences": [
    {
      "id": "be78e003-893f-4f3c-acb3-7bddf29f14ea",
      "content": "Redis 超时需要检查连接池和网络配置。",
      "relevance": 0.82,
      "authority": 90,
      "channels": ["KEYWORD", "VECTOR"],
      "citation": {
        "documentId": "96e8dc64-b86d-4c75-a484-46d7e16fd75c",
        "revisionId": "ec495719-02df-4c3d-bbd5-c926f7f64a61",
        "chunkId": "3fbd3485-f69a-455a-aefb-0f0836779648",
        "title": "用户登录故障排查",
        "sectionPath": ["用户中心", "Redis 超时"],
        "sourceUri": "https://kb.example.invalid/login-troubleshooting"
      }
    }
  ],
  "sufficient": true,
  "warnings": [],
  "generatedAt": "2026-08-03T05:00:00Z"
}
```

未经授权的空间会在召回前排除；任何 Retriever 返回的跨租户、越权或非活动
修订候选还会在 Runtime 被二次拒绝/过滤。

## 5. Obsidian Connector

API 进程必须先配置真实目录白名单：

```powershell
$env:KNOWLEDGE_OBSIDIAN_ALLOWED_ROOTS = 'C:\Knowledge,C:\TeamVaults'
```

创建连接器：

```powershell
$body = @{
  connectorId = 'obsidian-product'
  spaceId     = 'engineering'
  displayName = 'Product Vault'
  vaultName   = 'Product Wiki'
  vaultPath   = 'C:\Knowledge\ProductVault'
  authority   = 70
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/connectors/obsidian' `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $body
```

启动异步全量同步并轮询：

```powershell
$run = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/connectors/obsidian-product/sync' `
  -Headers $headers

Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/connectors/runs/$($run.runId)" `
  -Headers $headers
```

Run 返回 `status`、`recordsSeen`、`recordsChanged`、`errorCode` 和起止时间。
数据库约束拒绝同一租户/Connector 的并发 RUNNING Run。管理列表
`GET /api/v1/admin/connectors` 还返回 `lastRunId`，控制台据此在刷新后继续轮询。
当前“全量”表示每次扫描 Vault 中现存文件并幂等 upsert 新增/修改内容；不会
对已经移动或删除的历史文件执行对账，也不支持进程重启后的 Run 自动接管。

## 6. Retrieval Evaluation

以下端点需要 `knowledge-admin`：

```text
GET|POST /api/v1/evaluations/datasets
GET|POST /api/v1/evaluations/datasets/{datasetId}/cases
GET|POST /api/v1/evaluations/datasets/{datasetId}/runs
GET      /api/v1/evaluations/runs/{runId}
```

Case 保存 Query、Space、期望 Document/Chunk、Top K 和 Labels。Run 使用独立
有界线程池异步执行，保存 Recall@K、MRR、nDCG、结果数、错误码和成功 Case
的 `traceId`。单个 Case 失败不会丢失整个 Run。

创建数据集（HTTP 201）：

```json
{
  "name": "Phase 1 Retrieval",
  "description": "核心检索回归集"
}
```

响应包含数据集 `id`、`caseCount`、`runCount`、`status` 和 `createdAt`。使用该
`id` 创建 Case（HTTP 201）：

```json
{
  "query": "Redis 连接池耗尽如何排查？",
  "spaceIds": ["engineering"],
  "expectedDocuments": ["96e8dc64-b86d-4c75-a484-46d7e16fd75c"],
  "expectedChunks": [],
  "topK": 8,
  "labels": {"domain": "identity"}
}
```

`expectedDocuments` 与 `expectedChunks` 至少一个非空。启动 Run（HTTP 202）：

```json
{
  "topK": 8,
  "configuration": {"label": "phase1-acceptance"}
}
```

启动响应包含 `id` 和初始 `status=RUNNING`；随后轮询
`GET /api/v1/evaluations/runs/{runId}`，直到 `SUCCEEDED` 或 `FAILED`。终态响应
的 `metrics` 包含 `hitRate`、`recallAtK`、`mrr`、`ndcgAtK`，`results` 保存逐
Case 指标、`traceId` 或稳定 `errorCode`。当前 `configuration` 用于实验记录，
不会被解释为额外检索 Filter；Case 检索仍使用其 Space 和 Top K。

当前是进程内 Worker：应用重启后的 Run 租约恢复尚未实现。

## 7. 管理与观测

管理员读 API：

```text
GET /api/v1/admin/overview
GET /api/v1/admin/spaces
GET /api/v1/admin/documents
GET /api/v1/admin/documents/{documentId}/chunks
GET /api/v1/admin/connectors
GET /api/v1/admin/traces?limit=50
GET /api/v1/admin/traces/{traceId}
```

`GET /actuator/health` 公开；其他端点（包括 Prometheus）默认要求认证。Trace
只保存 ID、Query Hash、步骤、计数和耗时，不保存 Query/Chunk 正文或模型原始
响应。检索降级 Warning 随 `EvidenceBundle` 返回，不写入当前 Trace 读模型。

## 8. 错误与安全约束

- 401：JWT 无效、Audience 不匹配或缺少必要身份 Claim；
- 403：缺少管理员角色、主体/租户停用或无访问权限；
- 400：请求校验、Filter、URI 或 Obsidian 路径非法；
- 409 / `OPERATION_IN_PROGRESS`：同一 Connector 已有 RUNNING Run；
- 503 / `WORK_QUEUE_SATURATED`：Connector 或 Evaluation 有界队列暂时无法
  接纳任务；
- 500：未分类基础设施错误，使用 `requestId` 排查；
- 所有 HTTP 响应都通过 `X-Request-Id` Header 返回规范化关联 ID。进入
  Controller/Application 的已知失败使用 `ApiError`，其 Body 携带与 Header
  相同的 `requestId`，且不返回堆栈、Token、API Key 或文档正文；JWT 校验和
  Principal 入驻阶段的 401/403 由安全过滤链产生，稳定关联 ID 以 Header 为准，
  不承诺同样的 `ApiError` Body。
