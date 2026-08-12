# P1/P2 API 验收说明

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

## 3. 文档、原文件与修订

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
GET /api/v1/admin/documents/{documentId}/revisions
GET /api/v1/admin/documents/{documentId}/revisions/{revisionId}/chunks
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

`status` 支持 `ACTIVE`、`ARCHIVED`、`DELETED`。不传状态时默认不返回
`DELETED`；显式请求 `DELETED` 可用于管理审计。默认 Chunk 端点读取活动修订，
修订端点可查看指定历史修订的不可变 Chunk。

### 3.3 投影状态、重试和空间重建

```text
GET  /api/v1/documents/{documentId}/projections
POST /api/v1/documents/{documentId}/projections/{projectionType}/retry
POST /api/v1/spaces/{spaceId}/projections/rebuild
```

空间重建只为当前活动修订重新排队当前进程已启用的外部通道（例如
`KEYWORD`、`VECTOR`、`GRAPH`），不会重置正在运行的 Job。单文档状态和 retry
同样只作用于当前活动修订，历史修订的 DEAD Job 不会被管理操作重新执行：

```json
{
  "jobs": 24,
  "projectionTypes": ["KEYWORD", "VECTOR", "GRAPH"]
}
```

### 3.4 富文档上传和原文件

需要 `knowledge-admin`。当前支持 TXT/Markdown、HTML、PDF 和 DOCX：

```powershell
$form = @{
  file       = Get-Item '.\sample.pdf'
  spaceId    = 'engineering'
  externalId = 'sample.pdf'
  title      = '样例文档'
  language   = 'zh-CN'
  authority  = 80
}
Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/documents/files' `
  -Headers $headers `
  -Form $form
```

上传受源文件、解压大小、页数、Element 数、文本长度、归档条目和压缩比预算
约束。原文件保存在 MinIO，Bucket/Object Key 不对外暴露：

```text
GET /api/v1/documents/{documentId}/source
GET /api/v1/documents/{documentId}/source/content
GET /api/v1/documents/{documentId}/source/content?inline=true
```

`inline=true` 只对批准的安全媒体类型生效，并返回 `nosniff`、sandbox CSP 和
private/no-store；其余类型强制下载。Reader 必须同时通过空间 ACL 和文档授权。

### 3.5 生命周期

`expectedVersion` 是文档乐观版本：

```powershell
$body = @{ status = 'ARCHIVED'; expectedVersion = 3 } | ConvertTo-Json
Invoke-RestMethod `
  -Method Patch `
  -Uri 'http://localhost:8080/api/v1/documents/<documentId>/status' `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $body
```

目标支持 `ACTIVE`、`ARCHIVED`、`DELETED`。冲突返回 409。非活动文档会立即被
活动修订守卫排除，不应从 Keyword/Vector/Graph/Page 通道成为 Evidence；管理面
仍保留修订和原文件用于审计/恢复。

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

当前 API 只接受 `language` 和 `sourceType` Filter。PostgreSQL、Elasticsearch
和 Milvus 均执行精确匹配，向量通道不会静默忽略过滤条件。响应使用稳定 HTTP
DTO，不暴露 Java Value Object 的内部序列化：

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

Run 返回 `status`、`recordsSeen`、`recordsChanged`、`recordsDeleted`、
`errorCode` 和起止时间。数据库约束拒绝同一租户/Connector 的并发非终态 Run。管理列表
`GET /api/v1/admin/connectors` 还返回 `lastRunId`，控制台据此在刷新后继续轮询。
Run 先持久化为 `PENDING`，Coordinator Claim 后进入 `RUNNING`。低频恢复器会
接管 PENDING 或租约过期的 RUNNING；Fencing Token 阻止旧 Worker 提交。

每次完整成功扫描会提升 Snapshot Manifest，并把上一成功快照中缺失的文档
归档；失败或部分扫描不会删除。移动按旧 externalId 归档、新 externalId 创建。

## 6. Retrieval Evaluation

以下端点需要 `knowledge-admin`：

```text
GET|POST /api/v1/evaluations/datasets
GET|POST /api/v1/evaluations/datasets/{datasetId}/cases
GET|POST /api/v1/evaluations/datasets/{datasetId}/runs
POST     /api/v1/evaluations/datasets/{datasetId}/compare
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

启动响应包含 `id` 和初始 `status=PENDING`（快速 Claim 时也可能已为
`RUNNING`）；随后轮询
`GET /api/v1/evaluations/runs/{runId}`，直到 `SUCCEEDED` 或 `FAILED`。终态响应
的 `metrics` 包含 `hitRate`、`recallAtK`、`mrr`、`ndcgAtK`，`results` 保存逐
Case 指标、`traceId` 或稳定 `errorCode`。当前 `configuration` 用于实验记录，
不会被解释为额外检索 Filter；Case 检索仍使用其 Space 和 Top K。

Run 的 Case ID、Principal 和配置快照持久化；Coordinator 可以在进程重启后
恢复 PENDING/过期 RUNNING Run。队列立即饱和时返回 503，并把已 Claim Run
终止为明确失败。

基线对比示例：

```json
{
  "baselineRunId": "00000000-0000-0000-0000-000000000001",
  "candidateRunId": "00000000-0000-0000-0000-000000000002",
  "minimumRecallAtK": 0.85,
  "minimumMrr": 0.75,
  "maximumRegression": 0.02
}
```

响应返回 baseline/candidate 指标、delta、`passed` 和逐规则 violation。两次 Run
必须属于 URL 中同一 Dataset 且处于成功终态。

## 7. Graph 与 Wiki

### 7.1 Graph 探索

Graph 路由始终注册；未启用 Neo4j Adapter 时返回 HTTP 503 和
`GRAPH_CAPABILITY_UNAVAILABLE`，启用后执行受控关系查询：

```powershell
$body = @{
  query    = '订单服务依赖哪些组件'
  spaceIds = @('engineering')
  maxHops  = 2
  limit    = 50
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/admin/graph/search' `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $body
```

结果 Edge 含 Source/Target、Relation Type、Depth、Score，以及 Document/Revision/
Chunk/Excerpt Provenance。`maxHops` 为 1～3，`limit` 为 1～200；服务端固定 Cypher
模板，不执行客户端传入的 Cypher。

### 7.2 Wiki 页面

```text
POST /api/v1/admin/wiki/pages
GET  /api/v1/admin/wiki/pages?spaceId=engineering&status=PUBLISHED
GET  /api/v1/admin/wiki/pages/{pageId}
POST /api/v1/admin/wiki/pages/{pageId}/submit-review
POST /api/v1/admin/wiki/pages/{pageId}/reject
POST /api/v1/admin/wiki/pages/{pageId}/publish
POST /api/v1/admin/wiki/pages/{pageId}/archive
```

编译请求显式选择来源：

```json
{
  "spaceId": "engineering",
  "slug": "login-system",
  "title": "用户认证体系",
  "sources": [{
    "documentId": "96e8dc64-b86d-4c75-a484-46d7e16fd75c",
    "revisionId": "ec495719-02df-4c3d-bbd5-c926f7f64a61",
    "chunkId": "3fbd3485-f69a-455a-aefb-0f0836779648"
  }]
}
```

默认使用确定性抽取式编译；显式开启 generative 配置才调用 GLM。状态转换 Body
为 `{"expectedVersion": 2}`。只有 PUBLISHED 页面参加 Page Retrieval，而且返回
其活动原始来源 Chunk，不直接把生成 Markdown 当作权威 Citation。

## 8. 管理、观测与审计

管理员读 API：

```text
GET /api/v1/admin/overview
GET /api/v1/admin/spaces
GET /api/v1/admin/documents
GET /api/v1/admin/documents/{documentId}/chunks
GET /api/v1/admin/documents/{documentId}/revisions
GET /api/v1/admin/documents/{documentId}/revisions/{revisionId}/chunks
GET /api/v1/admin/connectors
GET /api/v1/admin/traces?limit=50
GET /api/v1/admin/traces/{traceId}
GET /api/v1/admin/audit-events?limit=50&offset=0
```

`GET /actuator/health` 公开；其他端点（包括 Prometheus）默认要求认证。Trace
只保存 ID、Query Hash、步骤、计数和耗时，不保存 Query/Chunk 正文或模型原始
响应。检索降级 Warning 随 `EvidenceBundle` 返回。

审计只记录变更方法的 tenant、principal、requestId、HTTP method、route pattern、
action、status、outcome 和 duration；不记录 Body、Token、Query、具体资源 URI。
Micrometer 检索指标只使用低基数标签。

## 9. Agent Client、OpenAPI 与 E2E

- OpenAPI：`docs/openapi.yaml`；
- Java Client：`knowledge-agent-client`，提供 `HttpKnowledgeSearchClient` 和
  `KnowledgeSearchTool`，Token 通过 `AccessTokenProvider` 注入；
- Playwright：`knowledge-console/e2e`，单 Worker，不自动启动服务或下载浏览器。

这些契约已写入源码；当前分支的真实 Infinity-Agent 联调和浏览器 E2E 仍待最终
验收，不能仅凭文件存在标记为通过。

## 10. 错误与安全约束

- 401：JWT 无效、Audience 不匹配或缺少必要身份 Claim；
- 403：缺少管理员角色、主体/租户停用或无访问权限；
- 400：请求校验、Filter、URI 或 Obsidian 路径非法；
- 409 / `OPERATION_IN_PROGRESS`：同一 Connector 已有 PENDING/RUNNING Run；
- 409：文档/Wiki `expectedVersion` 冲突或非法状态转换；
- 503 / `WORK_QUEUE_SATURATED`：Connector 或 Evaluation 有界队列暂时无法
  接纳任务；
- 500：未分类基础设施错误，使用 `requestId` 排查；
- 所有 HTTP 响应都通过 `X-Request-Id` Header 返回规范化关联 ID。进入
  Controller/Application 的已知失败使用 `ApiError`，其 Body 携带与 Header
  相同的 `requestId`，且不返回堆栈、Token、API Key 或文档正文；JWT 校验和
  Principal 入驻阶段的 401/403 由安全过滤链产生，稳定关联 ID 以 Header 为准，
  不承诺同样的 `ApiError` Body。
