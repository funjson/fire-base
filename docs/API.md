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
$capabilities = Invoke-RestMethod `
  -Uri 'http://localhost:8080/api/v1/document-processing-capabilities' `
  -Headers $headers

$body = @{
  spaceId = 'engineering'
  name    = 'Engineering Knowledge'
  description = '研发规范、接口说明与故障处理知识，供 Space Router 判断检索范围'
  documentProcessingConfig = $capabilities.defaultConfig
} | ConvertTo-Json -Depth 10

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/spaces' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

后端在同一事务中识别新建与精确重复请求。真正的新 Space 会先按当前部署能力校验完整
处理配置，再原子提交空间、不可变处理配置、创建者 Admin ACL 和空间级
`api-upload:<spaceId>` Connector；任一步失败都会整体回滚。成功响应为 HTTP 204，无响应体。
`description` 是必填的空间用途描述，既展示给用户，也供 Space Router 判断查询与 Space 的
匹配关系；它不是显示名称的重复文本。只有 `spaceId`、名称、描述和完整处理配置都相同的
重复请求才按幂等成功处理，且不会覆盖原记录；名称、描述或处理配置不同，或者同 ID Space
已经不是活动状态时返回 409。创建新处理契约必须使用新的 `spaceId`。

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

### 2.4 文档处理配置

以下端点需要 `knowledge-admin`：

```text
GET /api/v1/document-processing-capabilities
GET /api/v1/spaces/{spaceId}/document-processing-config
```

全局能力端点不依赖已有 Space，返回创建表单使用的完整 `defaultConfig`，以及当前部署可发现的
`availableParsers`、`availableChunkers`、`availableTokenizers` 和
`availableEmbeddingProfiles`。Space 端点返回创建时固化的 `parserSelections`、`cleaning`、
`chunker`、服务端生成的 `processingContract`、`runtimeContractMatched` 和审计信息，并附带
同一能力目录供测试广场展示。`processingContract` 保存抽取流程、来源规范化规则、逐格式
Parser、Cleaner、Chunker/Tokenizer 的实际实现材料与总 SHA-256；创建请求和 `testConfig`
都不能提交该字段。`runtimeContractMatched=false` 表示同名实现已变化或缺失：已有数据仍可
查询、已有投影仍按固化合同完成，但旧 Space 不再接受使用固化配置的正式抽取；一套完整且当前
可执行的 TEST_ONLY 本次测试配置仍可独立运行。Parser 目录用 `available` 与稳定的
`unavailableReason` 区分已安装能力和可安装但未启用的 Adapter；不可用项只用于展示，
Space 创建、测试覆盖和执行都会拒绝。每个 Parser 的
`outputCapabilities` 描述它保证输出的结构与来源定位能力，例如
`STANDARD_ELEMENTS`、`HIERARCHY`、`PAGE_NUMBER`、`BOUNDING_BOX`、
`FLAT_TABLE_TEXT`、`TABLE_STRUCTURE`、`NATIVE_ARTIFACT`；这是能力声明，不是解析质量评分。
当前内置 Markdown、HTML、DOCX 只声明扁平表格文本，不虚报可供表格专用 Chunk Adapter
消费的行列结构。

创建表单的 `defaultConfig` 会包含创建当时每种规范格式的明确 Parser 选择；Space 详情和
执行期只读取这份固化映射，不会用后来部署的新默认值补齐。安装新的格式 Adapter 不会改变
旧 Space 已有格式的行为；旧 Space 若上传其固化映射中没有的新格式会明确失败，正式启用该
格式需用当前完整配置创建新 Space。

`availableChunkers` 是当前部署的 Chunker Provider 目录。每项包含稳定 `id`、实现
`version`、`available`、稳定 `unavailableReason`、`requiredParserCapabilities` 和
`defaultProviderConfig`。该 ID 不是封闭枚举；当前内置 `STRUCTURAL` 与
`SEMANTIC_REFINEMENT`。后端会校验 Provider 已安装且可用，并拒绝任一有效 Parser
不满足 Chunker 前置能力的组合，不能只靠页面禁用选项保证正确性。

`chunker` 的公共字段为 `providerId`、`tokenizerId`、`minimumTokens`、`targetTokens`、
`maximumTokens`、`overlapTokens` 和 `providerConfig`，并满足
`1 <= minimumTokens <= targetTokens <= maximumTokens <= 65536`、
`0 <= overlapTokens < minimumTokens`。`STRUCTURAL` 的 `providerConfig` 必须是空对象。
`SEMANTIC_REFINEMENT` 必须且只能提交：

```json
{
  "contextSlices": 1,
  "embeddingProfileId": "zhipu/embedding-3@2048",
  "mergeSimilarityThreshold": 0.85,
  "splitSimilarityThreshold": 0.60
}
```

示例中的 `embeddingProfileId` 只说明格式，调用方必须使用当前响应目录中的实际 ID，不能
假设所有部署都安装智谱或使用 2048 维。

`contextSlices` 表示判断边界时两侧各补充的相邻 ElementSlice 数，只允许 0～2；绝对
余弦阈值必须满足 `-1 <= splitSimilarityThreshold < mergeSimilarityThreshold <= 1`。
相似度不高于拆分阈值时可增加软断点，不低于合并阈值时可删除软断点，中间区间保留
结构基线；标题、表格、代码等硬边界始终不能删除。`embeddingProfileId` 只能从
`availableEmbeddingProfiles` 选择，API 不接受任意模型、Endpoint 或凭据。

`availableTokenizers` 每项返回
`id/version/description/exactModelTokens/modelProfileId/available/unavailableReason`。
未启用但部署可识别的 Adapter 仍会出现在目录中，并由 `available=false` 与稳定原因码说明
为何不可选择；创建、测试或执行不可用 Tokenizer 都会被服务端拒绝。当前内置
`UTF8_BYTE_BUDGET` 以 UTF-8 字节数作为确定性预算代理，不等同于模型精确 Token 数，
因此返回 `exactModelTokens=false`。可选 `tokenizer-huggingface` 只从部署固定的本地
`tokenizer.json` 加载，并在启动时校验 SHA-256；`exactModelTokens=true` 时
`modelProfileId` 必须匹配当前 Embedding Profile，语义 Provider 还必须匹配其
`providerConfig.embeddingProfileId`，否则创建或测试配置会被拒绝。这是受信部署配置的
固定绑定，不等于已与真实 Serving 自动对拍；上线前仍需运行目标模型的 Token Golden 数据集。

运行时注册表、Space 选择链和处理契约已经具备接入额外 `KnowledgeChunkerProvider` Adapter
和 `TokenCounter` Adapter 的边界；当前仍没有第三方 Chunker Provider。Provider 专属 JSON
由服务端按已安装 Provider 契约校验，不能把扩展点宣传成任意配置协议或动态插件市场。
可选 `parser-docling` 启用后会在 PDF 与 DOCX 格式下各增加一个 Parser 选项，内置
PDFBox/POI 仍是部署默认值；Docling partial、Schema 漂移或响应损坏不会静默回退。

`cleaning` 分别配置 `header`、`footer`、`pageNumber`、`watermark` 和 `frontMatter`，
每项只允许 `KEEP`、`REMOVE`、`METADATA_ONLY`。这些动作只处理 Parser 明确标注的
文档角色；隐藏内容是服务端不可关闭的安全清洗边界，不在该配置中暴露。

Space 处理配置从创建开始不可变，没有 PUT、乐观更新或执行期默认值补写入口。正式配置或同名
实现版本需要变化时，管理员使用测试广场验证目标配置，再创建新 Space、重新摄取数据，并由
调用方显式切换 `spaceId`；系统不按固化合同路由历史实现。测试请求可以携带一次性的完整测试
配置；服务端为它计算当前实际合同并只写入本次 Run 快照，不写回 Space。

### 2.5 多文件抽取任务与 Space 测试广场

以下端点需要 `knowledge-admin`：

```text
POST /api/v1/spaces/{spaceId}/extraction-runs
POST /api/v1/spaces/{spaceId}/ingestion-runs
GET  /api/v1/spaces/{spaceId}/extraction-runs
GET  /api/v1/spaces/{spaceId}/extraction-runs/{runId}
POST /api/v1/spaces/{spaceId}/extraction-runs/{runId}/cancel
GET  /api/v1/spaces/{spaceId}/extraction-runs/{runId}/items/{itemId}/source
GET  /api/v1/spaces/{spaceId}/extraction-datasets
POST /api/v1/spaces/{spaceId}/extraction-datasets/{datasetId}/runs
```

上传创建请求使用 `multipart/form-data`，重复字段名 `files` 表示多个文件，`language` 为可选
BCP 47 标签；可选 `testConfig` Part 必须使用 `application/json`。请求受文件数、单文件
大小、批次总字节数以及 Servlet Multipart 总量共同约束；服务端逐文件完成有界读取、
SHA-256 和 OSS 写入，不构造 `List<byte[]>`。创建成功返回 HTTP 202。一个租户 Space 同时只
允许一个 `QUEUED/RUNNING/CANCEL_REQUESTED` Run。

`extraction-runs` Multipart 与 Dataset 端点创建 `TEST_ONLY` Run：后台只读取 OSS、调用生产摄取共用的
`ExtractionEngine`，不会创建正式 Document、Revision 或索引。统一任务模型同时提供
逐文件诊断、预览与硬门禁。两个端点都接受可选的完整 `testConfig`（`parserSelections`、
`cleaning`、`chunker`）；Dataset 端点在 JSON Body 中携带该字段。该配置没有版本或保存语义，
服务端会复用同一字段规范化与能力校验链，校验测试配置实际选择的 Parser、Chunker、Tokenizer
和 Provider 组合，再为本次 Run 生成权威快照；测试覆盖不要求补齐旧 Space 创建后新增的格式。
即使 Space 固定 Adapter 后来下线，一套完整且当前可执行的测试配置仍可运行，但不会成为正式
配置或构成静默回退。测试配置本身不可用时请求直接失败，且不会创建任务或写入原件。

`ingestion-runs` 创建正式 `INGEST` Run。请求除重复的 `files` 外，还必须携带
`application/json` 类型的 `manifest`，其中 `items` 与文件按顺序一一对应，每项保存稳定
`externalId`、检索 `title` 和 `authority`。上传入口不接受 Parser、Cleaner、Chunker 或
Tokenizer 临时覆盖。相同活动来源和相同 SHA-256 返回 `SKIPPED_DUPLICATE`；相同
`externalId` 但内容不同返回 `EXTERNAL_ID_CONFLICT`，不会隐式覆盖既有 Revision。
两个 mode 由独立 Processor 领取，TEST_ONLY Processor 从结构上不具备发布依赖。

创建任务时会固化完整有效的 Parser/Cleaner/Chunker/Tokenizer 配置、实际实现
`processingContract` 和本次 `normalizerContract`。正式 Run 在保存原件前确认当前部署仍匹配
Space 固化合同；Worker 领取后、处理任何 Item 或读取 OSS 前再次复核，漂移时整单以
`PROCESSING_CONTRACT_MISMATCH` 失败。服务端还会重新计算快照指纹并拒绝客户端伪造或损坏的
快照。使用本次测试配置时，`configVersion` 仍记录 Space 的固定版本 1；实际实验语义和有效指纹
来自 Run 快照，指纹不包含创建主体或时间。临时配置只允许 `TEST_ONLY`，正式 `INGEST` 会拒绝
它，也不会修改 Space 生效配置。正式发布事务最终同时校验版本 1 和固化合同指纹，避免抽取与
结构化事实写入之间发生实现漂移。

Baseline 对比要求双方属于同一 tenant/space、同一个不可变 Dataset，并使用相同的规范化
`language`。对比基线必须在创建本次测试 Run 时已经是 `TEST_ONLY`、`SUCCEEDED`，且真实 Gate
结果为 `PASSED` 或 `FAILED`；`NOT_EVALUATED` 和 `ERROR` 都不可作为可比基线。页面逐字段比较
两个不可变配置快照。

Run 状态为 `QUEUED/RUNNING/CANCEL_REQUESTED/SUCCEEDED/FAILED/CANCELLED`。排队任务取消后
立即终止；运行任务在文件边界协作式取消。INGEST Item 进入 `PUBLISHING` 后处于不可安全
取消的事务窗口，取消返回 409 `EXTRACTION_PUBLICATION_IN_PROGRESS`；完成或失败后才可继续。
调度只做数据库原子领取，并使用进程级 `workerId + heartbeatAt` 防止失联旧执行覆盖接管
结果，不暴露 Lease/Fence Token 领域对象。

原件在失败或取消后仍保留在 OSS。受控下载端点返回 attachment/no-store/nosniff 响应，
不会暴露 MinIO Bucket、物理 Key 或 storageId。成功 Item 会保存有界 Element/Chunk 预览，
包括结构、清洗决策、Element 内 SourceSpan 和规范化 Artifact UTF-16 全局范围；预览限制
Element/Chunk 数、单段正文和总正文，不保存完整 Artifact、`contextualText` 或模型响应，且
禁止进入日志。

测试广场从服务端受版本控制 Dataset 创建真实 Run，Golden Sources 仍走相同 OSS → Parse →
Clean → Chunk 链路。`ExtractionObservationFactory` 使用实际 Chunker 注册的同一个
TokenCounter 重算最终 `contextualText` Token，再由统一 Runner 计算解析、Token 越界、非法
SourceSpan、来源核算率和静默截断五项硬门禁。Run `SUCCEEDED` 与 Gate `PASSED` 是两个独立
结论；未执行真实 Dataset 评测时 Gate 必须保持 `NOT_EVALUATED`，不能根据诊断计数推测通过。

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

Markdown API 与 Obsidian Markdown 记录继续复用同步 `DocumentIngestionPipeline`；正式文件
摄取只使用 Space 级异步 Run，并复用同一个 `ExtractionEngine` 与运行时发布服务，统一执行
稳定身份、Parser 选择、Clean、Chunk、事务写入和投影调度。旧同步单文件上传接口已经删除，
避免两套覆盖、OSS 清理和错误处理语义并存。
`DocumentParserRegistry` 会规范化媒体类型；缺失或 `application/octet-stream` 可按扩展名选择，
但显式声明的不支持类型或媒体类型/扩展名冲突会返回 400，不能靠扩展名静默放宽。

空间默认选择确定性结构 Provider `STRUCTURAL`。设置
`KNOWLEDGE_SEMANTIC_CHUNKING_ENABLED=true` 会让内置 Provider
`SEMANTIC_REFINEMENT` 可供 Space 选择，
但不会自动切换已有配置；该能力同时要求
`KNOWLEDGE_EMBEDDING_ENABLED=true`。默认模型阶段最多 128 个输入、12 秒总时限，
并由单线程有界执行器隔离；Provider 失败、超时或返回契约错误会使本次摄取明确失败，
不会在相同 `processorVersion` 下静默发布 Baseline 结果。`SEMANTIC_REFINEMENT` 在
结构允许调整的位置新增断点，或删除 `STRUCTURAL` Baseline 的软断点，再由统一物化链生成 Chunk；当前
没有跳过结构、大小和引用安全边界的纯语义模式。

### 3.2 管理列表与 Chunk

需要 `knowledge-admin`：

```text
GET /api/v1/admin/documents?spaceId=engineering&status=ACTIVE&title=网关&source=obsidian&keywordStatus=SUCCEEDED&vectorStatus=SUCCEEDED&minimumChunkCount=1&maximumChunkCount=200&updatedFrom=2026-08-01T00:00:00Z&updatedTo=2026-08-31T23:59:59Z&limit=50&offset=0
GET /api/v1/admin/documents/{documentId}
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

所有过滤条件都可独立使用或组合使用：`title` 按文档标题模糊匹配；`source` 同时匹配来源
类型、来源 URI 和原文件名；`keywordStatus`、`vectorStatus` 支持 `PENDING`、
`SUCCEEDED`、`FAILED`、`SKIPPED`；Chunk 数量按活动修订统计；`updatedFrom` 与
`updatedTo` 使用 ISO-8601 时间并包含边界。`limit` 默认为 50、最大 200，`offset`
默认为 0。

`status` 支持 `DRAFT`、`ACTIVE`、`DEPRECATED`、`ARCHIVED`、`DELETED`。不传状态时
默认不返回 `DELETED`；显式请求 `DELETED` 可用于管理审计。文档详情端点返回与列表项
相同的摘要结构，并按租户隔离；不存在或属于其他租户时返回 404。默认 Chunk 端点读取
活动修订，修订端点可查看指定历史修订的不可变 Chunk。

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

### 3.4 异步多文件摄取和原文件

需要 `knowledge-admin`。当前支持 TXT/Markdown、HTML、PDF 和 DOCX：

```text
POST /api/v1/spaces/engineering/ingestion-runs
Content-Type: multipart/form-data

files: @sample.pdf
files: @operations.docx
language: zh-CN
manifest: application/json
{
  "items": [
    {"externalId":"manual/sample.pdf","title":"样例文档","authority":80},
    {"externalId":"manual/operations.docx","title":"生产运行手册","authority":90}
  ]
}
```

响应为 HTTP 202 和 `mode=INGEST` 的 Run 详情。状态、逐文件结果、取消和原件下载复用
2.5 节的 `extraction-runs` 查询 API。上传受文件数、批次总字节、单文件、解压大小、页数、
Element 数、文本长度、归档条目和压缩比预算约束。原文件在解析失败、重复或取消后仍保存在
MinIO，Bucket/Object Key 不对外暴露。发布成功后还可按 Document 读取权威原件：

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
  query           = 'Redis 超时'
  spaceIds        = @('engineering')
  topK            = 5
  filters         = @{ language = 'zh-CN' }
  constraints     = @{
    relaxableFilters = @{}
    narrowingFilters = @{ sourceType = 'UPLOAD' }
  }
  retrievalTarget = '获得连接池、网络和下游错误码证据'
  evidenceRequirements = @(
    @{ id = 'pool'; description = '连接池耗尽或连接泄漏证据' }
    @{ id = 'network'; description = 'Redis 网络时延证据' }
  )
  configurationOverride = @{
    reranker = @{ enabled = $false }
  }
  testMode = $true
} | ConvertTo-Json -Depth 12

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/knowledge/query' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

当前 API 只接受 `language` 和 `sourceType` Filter。`filters` 是调用方硬约束，
任何模型和 `RELAX_CONSTRAINTS` 节点都不能移除；`constraints.relaxableFilters` 首轮生效，
只有调用方显式放入后才允许确定性放宽；`constraints.narrowingFilters` 首轮不生效，
只允许 `NARROW_CONSTRAINTS` 从这些 Agent 已提供的条件中选择并加入，不会由模型发明新条件。
三个集合的字段不得重叠。PostgreSQL、Elasticsearch
和 Milvus 均执行精确匹配，向量通道不会静默忽略过滤条件。Knowledge Runtime 无服务端会话；
调用方应提交已经独立化的本轮查询。`retrievalTarget` 最多 4000 字，
`evidenceRequirements` 最多 32 项且 ID 在本次请求中唯一，供 Coverage Judge 判断证据是否充分。

`configurationOverride` 是本次请求的强类型局部覆盖，未出现字段继承首个实际访问 Space 的当前配置；
合并后仍受部署硬上限约束，超限返回 400，不会静默裁剪或写回 Space。`testMode=true` 只改变
观测用途为 `TEST_PLAZA`，不绕过真实检索链路、授权或资源预算。响应使用稳定 HTTP DTO，
不暴露 Java Value Object 的内部序列化：

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
        "sourceUri": "https://kb.example.invalid/login-troubleshooting",
        "sourceSpans": [
          {
            "elementId": "75a979c0-b51c-4bba-ae58-0122780af853",
            "startOffset": 0,
            "endOffset": 19,
            "pageNumber": null
          }
        ]
      }
    }
  ],
  "sufficient": false,
  "terminalStatus": "NOT_EVALUATED",
  "stopReason": "COVERAGE_DISABLED",
  "degraded": false,
  "visitedSpaceIds": ["engineering"],
  "configurationFingerprints": [
    "d1501dc9baefa3671ea42757436fafa973cb5dd4166768659c39e912f8efad98"
  ],
  "warnings": [],
  "generatedAt": "2026-08-03T05:00:00Z"
}
```

未经授权的 Space 会在路由和召回计划前排除；活动修订约束作为每个召回分支的固有过滤条件执行，
不额外建立一个可由模型修改的授权或“二次校验”业务节点。

`sourceSpans` 的 `[startOffset,endOffset)` 是相对 `KnowledgeElement.content` 的 UTF-16
字符范围，可直接供 Java/Web 文本高亮；`pageNumber` 是 Parser 能可靠提供时的一基页码。
V16 之前的历史 Chunk 无法反推精确范围，因此可能返回空数组。当前 API 不提供 Bounding Box，
也尚未完成 PDF 原件版面高亮。Neo4j Graph 当前只保存 Chunk 级 Provenance，因此只由 Graph 通道
提供的 Evidence 也会返回空范围；同一 Chunk 同时被 PG/ES/Milvus/Page 命中时，RRF 会优先保留
带非空范围的代表候选，避免 Graph 分数较高时覆盖可高亮引用。

Console 和 Java Client 已同步 `sourceSpans` 响应类型，但当前 Console 只具备类型契约，尚未实现
Element/PDF 原件高亮交互。

Chunk 的展示/引用正文与语义正文已经分离：响应 `content` 始终是原始 Chunk 正文；章节路径增强后的
`contextualText` 只用于向量投影，不通过本 API 暴露，也不会污染引用。

### 4.1 Space 检索配置

每个 Space 都有一条当前检索配置指针和不可变历史修订：

```text
GET /api/v1/spaces/{spaceId}/retrieval-configuration
PUT /api/v1/spaces/{spaceId}/retrieval-configuration
GET /api/v1/spaces/{spaceId}/retrieval-configuration/history?limit=20
```

读取要求当前主体可读该 Space；更新要求 `knowledge-admin`。PUT 必须提交页面读取到的
`expectedRevision` 和完整 `configuration`。相同修订、相同语义内容的网络重试按幂等成功；
过期修订且内容不同返回 409 `SPACE_RETRIEVAL_CONFIGURATION_REVISION_CONFLICT`。

完整配置包含首轮术语增强、四通道分支与 Weighted RRF、Reranker、Coverage、最大检索尝试、
七个固定 Chain 节点开关和跨 Space 上限。固定 Chain 的权威顺序是：

```text
GAP_QUERY -> PRF -> RELAX_CONSTRAINTS -> NARROW_CONSTRAINTS
          -> STEP_BACK -> HYDE -> NEXT_SPACE
```

调用方只能开关节点，不能重排。`NEXT_SPACE` 与 `crossSpace.enabled` 必须一致；模型只对服务端
授权 Space 列表排序，不能新增 Space 或改变租户。索引代际、Embedding 维度和 Tokenizer 不属于
此可变配置；需要改变索引合同应创建新 Space。

### 4.2 Query Planner、Coverage 与 Reranker

原查询 Q0 永远参与召回。开启 Query Planner 后，模型以结构化输出生成有限 Variant；超时、过载、
结构错误或 Provider 失败会使用 Q0 继续。每次尝试按配置生成 Keyword/Vector/Graph/Page 有限物理
分支，并用一个公共 RRF 常数与各分支权重融合；不能用 Retriever 原始分数再次乘权。

Coverage 开启且有 Evidence Requirements 时，会在每次尝试后判断是否充分。证据不足才按固定
Chain 选择下一个适用节点；达到阈值、预算耗尽、Chain 结束和 Judge 技术失败使用不同
`terminalStatus/stopReason`。Coverage 分数是运行诊断，不是 Recall。

Reranker 成功时，Evidence `relevance` 是本次查询内的最终模型分数；关闭时保持 RRF 顺序。
超时、过载、结构错误或 Provider 失败时，整个候选顺序与分数回退到 RRF，不能混用部分模型排序。
不同查询或不同模型的 `relevance` 不能当成全局绝对分数比较。

### 4.3 按请求查询逐层观测

检索响应的 `requestId` 可以下钻最新一次 execution：

```powershell
Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/retrieval-observations/requests/$($result.requestId)" `
  -Headers $headers
```

报告包含用途、完整性、缺失序列、实际访问的 Space 配置、按 sequence 排序的安全阶段事实和
版本化指标事实。公开响应不包含原始事件 payload、查询正文、候选/Chunk 正文或模型原始响应。
报告按 JWT 租户读取，并要求当前主体仍可访问报告涉及的全部 Space；不存在或其他租户的 request
返回 404 `RETRIEVAL_OBSERVATION_NOT_FOUND`。

运行指标可以按 Space、配置指纹、策略、尝试、组件模型、索引版本、状态、停止原因、用途和时间片
呈现。`ONLINE` 与 `TEST_PLAZA` 必须按 purpose 隔离。没有绑定不可变 Dataset/Case Gold 时，
这些事实不能被宣传为 Recall、MRR 或 nDCG；完整验收步骤见
[企业检索升级验收规范](acceptance/RETRIEVAL-ACCEPTANCE.md)。

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

仓库中的最小检索 fixture 见
[`acceptance/fixtures/retrieval-evaluation-v1.json`](acceptance/fixtures/retrieval-evaluation-v1.json)。
当前没有该 JSON 的自动 Loader；先正式摄取同目录两个 Markdown Source，把占位符替换为本次真实
Document UUID，再按 `runnerCases` 创建 API Case。`acceptanceOnlyCases` 的不可回答样例没有伪造
Gold 标签，只能使用知识查询 API 验收。

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
GET /api/v1/admin/documents/{documentId}
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

这些契约已写入源码。抽取工作台的真实浏览器 E2E 已覆盖 TEST_ONLY、精确 Tokenizer、
取消，以及正式 INGEST 的成功、重复与冲突；外部 Infinity-Agent 跨仓联调仍待验收，
不能从本仓库浏览器结果外推。

## 10. 错误与安全约束

- 401：JWT 无效、Audience 不匹配或缺少必要身份 Claim；
- 403：缺少管理员角色、主体/租户停用或无访问权限；
- 400：请求校验、Filter、URI 或 Obsidian 路径非法；
- 409 / `OPERATION_IN_PROGRESS`：同一 Connector 已有 PENDING/RUNNING Run；
- 409 / `SPACE_ALREADY_EXISTS_WITH_DIFFERENT_CONFIGURATION`：同一 Space ID 已存在，但名称、状态或不可变处理配置与创建请求不同；
- 409：文档/Wiki `expectedVersion` 冲突或非法状态转换；
- 503 / `WORK_QUEUE_SATURATED`：Connector 或 Evaluation 有界队列暂时无法
  接纳任务；
- 500：未分类基础设施错误，使用 `requestId` 排查；
- 所有 HTTP 响应都通过 `X-Request-Id` Header 返回规范化关联 ID。进入
  Controller/Application 的已知失败使用 `ApiError`，其 Body 携带与 Header
  相同的 `requestId`，且不返回堆栈、Token、API Key 或文档正文；JWT 校验和
  Principal 入驻阶段的 401/403 由安全过滤链产生，稳定关联 ID 以 Header 为准，
  不承诺同样的 `ApiError` Body。
