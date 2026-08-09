# Phase 1 缺陷修复与架构收敛方案

> 日期：2026-08-03  
> 输入：`TEST-REPORT.md`、`ARCHITECTURE-AUDIT.md`、`TEST-CASES.md`  
> 目标：修复 Phase 1 验收缺陷，同时处理“Control Plane 承担过多应用与持久化职责”这一架构偏差  
> 原则：保持现有工程风格、优先可读性、不增加无必要的抽象

## 1. 目标状态

本轮完成后，项目应达到“第一阶段可验收”，含义是：

1. 管理员和 Reader 均有明确、可工作的认证与权限路径；
2. 空间、Markdown 写入、文档管理、关键词/向量检索、Evidence、Trace、评测可形成稳定闭环；
3. 文档身份、活动修订与外部索引不会因回滚、并发或任务乱序返回错误知识；
4. Control Plane 不再直接执行 SQL，也不直接构造 Obsidian 等具体 Connector；
5. 前后端契约、运行配置和 README 与实际行为一致；
6. 所有已修复问题都有自动化回归测试。

该目标是可继续演进的 Phase 1 基线，不等价于完整生产版本。

## 2. 本轮范围

### 2.1 必须修复

| 来源 | 问题 | 本轮处理 |
|---|---|---|
| F-001 / A-003 | 相同 externalId 跨空间污染文档身份 | 修复 |
| F-002 / A-004 | A→B→A 无法恢复活动修订、并发修订号风险 | 修复 |
| F-003 | 文档管理页面列表接口 500 | 首个修复项 |
| F-004 | HTTP 与 Evidence requestId 不一致 | 修复 |
| F-005 | 危险来源 URI 可进入可点击 Citation | 修复 |
| F-006 / A-005 | Reader、Principal 和 ACL 无完整路径 | 修复 Phase 1 所需闭环 |
| F-007 | localhost、127、preview 的 OIDC/CORS 不一致 | 修复 |
| F-008 | 容器启动但 Hybrid 通道默认未启用、无回填 | 修复验收 Profile 与回填 |
| F-009 | Milvus 模块依赖不完整 | 修复 |
| F-010 | 未知前端路由白屏 | 修复 |
| A-001 | Milvus 可返回旧修订 | 修复 |
| A-002 | ES 旧任务可能逆序覆盖新修订 | 修复 |
| 架构偏差 3 / A-010 | Control Plane 直接 SQL、服务过大 | 收敛 |
| A-011 | Control Plane 直接构造 Obsidian | 用一个轻量 Provider Registry 收敛 |

### 2.2 本轮不扩张

以下能力继续保持明确的 `NOT_SUPPORTED`，不混入修复：

- Neo4j Graph Retrieval；
- LLM Wiki Compiler；
- PDF/DOCX/HTML/Office 解析；
- MinIO 原始文件体系；
- 完整生产部署、跨区域高可用；
- UI 全面改版；
- 通用工作流、消息总线或插件市场。

这些能力不应阻塞 Phase 1，但 README 必须准确说明。

## 3. 控制复杂度的明确约束

### 3.1 不新增 Maven 模块

继续使用现有模块：

```text
knowledge-domain
knowledge-spi
knowledge-runtime
knowledge-evaluation
knowledge-ingestion
connector-obsidian
store-postgres
store-elasticsearch
store-milvus
control-plane
knowledge-console
```

### 3.2 只增加四个按用例聚合的持久化端口

不为每张表创建 Repository，不创建 `BaseRepository<T>`。

计划增加：

1. `KnowledgeAdministrationStore`
   - Overview、空间/文档/Chunk/Connector/Trace 管理查询；
2. `KnowledgeGovernanceStore`
   - 空间创建、Principal 入驻、ACL grant/revoke/list、可访问空间；
3. `EvaluationStore`
   - Dataset、Case、Run、Case Result 持久化；
4. `ConnectorStateStore`
   - Connector Definition、Checkpoint、Sync Run 状态。

端口内使用少量明确 record 表达读模型，避免把 HTTP DTO 或 JDBC 类型暴露给 SPI。

对应 PostgreSQL 实现全部放入 `store-postgres`：

```text
PostgresKnowledgeAdministrationStore
PostgresKnowledgeGovernanceStore
PostgresEvaluationStore
PostgresConnectorStateStore
```

### 3.3 Control Plane 保持薄层

修复后的边界：

```text
Controller
  -> 参数校验 / JWT Principal
Application Service
  -> 权限检查 / 用例编排
SPI Port
  -> store-postgres / connector provider / external adapter
```

验收规则：

- `control-plane/application` 中不得出现 `JdbcTemplate`；
- 不得出现 `new ObsidianVaultConnector(...)`；
- Controller 不直接调用数据库或厂商 SDK；
- 不引入 Command Bus、Event Bus、通用 Mapper 框架；
- 只在类确实承担两个独立生命周期时拆分，例如 Service 与后台 Worker。

### 3.4 Connector 只使用一个轻量扩展点

增加 `SourceConnectorProvider`：

```text
type()
open(definition)
```

`ConnectorApplicationService` 直接把注入的 Provider 列表按 type 建成 Map，不再增加额外 Registry 框架。Obsidian 模块实现一个 Provider；后续新增 Connector 只新增 Adapter Bean，不修改核心同步流程。

## 4. 实施切片

## Slice 0：建立失败基线与文档索引

先补测试，不改生产逻辑：

- 新增“文档管理页面首次加载”显式用例；
- 为文档列表四种过滤组合建立 PostgreSQL 集成测试；
- 建立跨空间 externalId、A→B→A、旧投影乱序、Reader 权限回归用例；
- README 顶部增加“验收与已知问题”入口，链接测试和修复状态。

完成标准：

- 测试能够稳定复现现有缺陷；
- 每个缺陷都有唯一 ID，修复后原测试直接转绿。

## Slice 1：修复文档页面，同时收敛 Control Plane SQL

不在旧 `KnowledgeManagementService` 内做临时 SQL Patch，而是一次完成正确边界：

1. 增加 `KnowledgeAdministrationStore`；
2. 将管理查询 SQL 移入 `PostgresKnowledgeAdministrationStore`；
3. 文档列表按实际存在的 Filter 动态追加 WHERE，彻底移除 `? IS NULL`；
4. `KnowledgeManagementService` 只负责 Admin 检查和 API View 映射；
5. 保持现有 API URL 和 JSON 兼容；
6. 前端增加分页、加载失败的稳定错误和 requestId 展示。

必须覆盖：

```text
无过滤
仅 spaceId
仅 status
spaceId + status
空白过滤值
limit / offset 边界
tenant 隔离
```

完成标准：

- 文档管理页面可直接打开；
- 四种过滤组合均为 200；
- 超过 100 条文档可分页访问；
- Control Plane 的管理查询不再包含 SQL。

## Slice 2：修复文档身份、修订和投影一致性

### 2.1 空间级身份

- API 上传 Connector 改为稳定的空间级 ID，例如 `api-upload:<spaceId>`；
- 文档新身份包含 `tenant + space + connector + externalId`；
- `KnowledgeCatalog` 增加按完整 Source Key 查找已有 Document ID，兼容旧数据 ID；
- Flyway V5 将唯一约束调整为：

```text
tenant_id + space_id + connector_id + external_id
```

- 迁移现有 `api-upload` 数据到对应空间级 Connector；
- 增加数据一致性检查：Document/Connector/Chunk 的 space 必须一致。

### 2.2 修订发布

- 只有请求 Hash 等于当前活动修订时才返回 `changed=false`；
- 命中历史非活动 Hash 时执行显式“重新激活”，并重新产生投影任务；
- 修订号在 `PostgresKnowledgeWriter` 的文档行锁事务中分配，移除事务外 `MAX+1`；
- 活动修订切换和 Projection Job 写入保持同一事务。

### 2.3 外部索引发布

采用一个最小发布保护，不实现复杂分布式事务框架：

- Projection Job 带单调的文档 generation/revision number；
- Worker 发布前验证仍为当前活动修订；
- ES/Milvus Adapter 只接受不小于当前已发布 generation 的写入；
- 旧任务完成时标记为 superseded/no-op，不删除新修订；
- 新修订发布后清理同文档旧投影；
- 检索结果仍通过活动修订和 ACL 二次校验。

完成标准：

- `CONS-001` 至 `CONS-007` 通过；
- A→B、A→B→A、A/B 逆序投影最终都只返回当前活动知识；
- 归档或撤权后 ES/Milvus 不返回旧内容。

## Slice 3：修复 Principal、ACL 与 Reader 链路

保持 Keycloak 只负责认证、业务库负责授权：

1. `KnowledgeGovernanceStore` 承接空间创建、Principal 与 ACL SQL；
2. 已认证用户首次进入时进行受控 Principal upsert；
3. 增加空间 ACL 的 list/grant/revoke API；
4. 增加 `GET /api/v1/spaces/accessible`；
5. `AccessScope` 使用显式语义：

```text
ALL
ONLY(ids)
DENY_ALL
```

6. Reader 前端只显示可用导航和空间；
7. Retrieval Lab 不再调用管理员空间接口；
8. Keycloak 幂等配置同步 Client、角色、测试用户和 mapper，而不只同步 Console Client。

完成标准：

- `demo-admin` 和 `demo-reader` 均可登录；
- Reader 只能检索被授权空间；
- 未授权空间不可见且不可通过直接 API 访问；
- grant/revoke 后权限立即生效；
- 前端不再向 Reader 展示无权使用的管理操作。

## Slice 4：HTTP、安全与前端契约

- requestId 由入口 Filter 生成一次并传给 `KnowledgeQuery`，下游禁止重新生成；
- HTTP Header、Error Body、Evidence、Trace、日志使用同一个 ID；
- Source URI 使用配置化协议白名单，默认允许实际业务使用的 `http/https/obsidian` 及明确内部协议；
- HTTP 层使用稳定 DTO，把 `TenantId`、`DocumentId` 映射为字符串；
- 统一 `generatedAt` 字段名；
- 对缺少必需 JWT Claim 的请求返回 401；
- 配置 API audience 校验，并限制 `system_principal` 的受信 Client；
- localhost/127/preview 的 Keycloak 与 CORS 配置统一；
- Vite dev/preview 固定端口并启用 `strictPort`；
- 前端增加 404 和 Error Boundary；
- Token 刷新失败时清理会话并重新认证。

完成标准：

- F-004、F-005、F-007、F-010 通过；
- API Schema/序列化契约测试通过；
- Admin/Reader 两类浏览器流程无持续 401、403 或白屏。

## Slice 5：Hybrid 运行基线与 Adapter 独立性

- `store-milvus` 补齐直接 SLF4J API 依赖；
- 增加一个明确的 `acceptance` Profile：
  - PostgreSQL、Elasticsearch、Milvus、GLM 均启用；
  - 启动时检查模型维度和 Adapter 健康；
  - 声明 Hybrid 时，关键通道不可用应 fail-fast，而不是静默变成 PostgreSQL-only；
- 为 Adapter 后启提供按空间执行的 Backfill/Rebuild 命令；
- Milvus、ES 对 `sourceType`、`language` 等不支持的 Filter 明确报 Warning/拒绝通道，不静默放宽；
- 控制台显示实际启用通道、降级原因和 Projection 状态。

完成标准：

- 精确关键词问题和语义改写问题均可命中；
- PG、ES、Milvus 真实集成测试独立通过；
- 关闭某一通道时有稳定 Warning；
- 声明 acceptance Profile 时不会无提示缺失向量/ES。

## Slice 6：Evaluation/Connector 持久化下沉

### Evaluation

- SQL 移至 `PostgresEvaluationStore`；
- `EvaluationApplicationService` 保留用例编排；
- 运行执行部分拆为 `EvaluationRunWorker`，只拆这一条自然异步边界。

### Connector

- SQL 移至 `PostgresConnectorStateStore`；
- 使用 `SourceConnectorProvider` 选择 Obsidian；
- 同步执行部分拆为 `ConnectorSyncWorker`；
- 同一 Connector 运行中使用数据库约束/single-flight 防止重复启动；
- 前端按 runId 轮询到终态。

完成标准：

- `control-plane/application` 全部移除 JDBC；
- Connector Service 不引用 Obsidian 具体类；
- Evaluation、Connector 现有 API 保持兼容；
- 基础成功、失败、重复启动回归测试通过。

## Slice 7：完整验收与文档维护

更新：

- `README.md`
  - 当前真实能力；
  - acceptance Profile；
  - Admin/Reader 账号及 Keycloak 配置同步；
  - 前后端启动方式；
  - Phase 1 验收命令；
  - 明确未支持能力；
- `docs/ARCHITECTURE.md`
  - Control Plane、Port、PostgreSQL Projection 边界；
- `docs/PHASE-1-STATUS.md`
  - 只记录实际通过的能力；
- `docs/API.md`
  - 文档分页、ACL、可访问空间、requestId 契约；
- `TEST-REPORT.md`
  - 每个 F-* 标记 `FIXED/VERIFIED` 或保留原因；
- `TEST-CASES.md`
  - 固化全部回归用例。

最终验证：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify

cd knowledge-console
npm.cmd run lint
npm.cmd run test
npm.cmd run build
```

外部契约验证单独执行并出结果：

```text
PostgreSQL IT
Elasticsearch IT
Milvus IT
GLM Embedding IT
API/Keycloak 冒烟测试
Admin/Reader 浏览器验收
```

用户当前运行的服务不由修复过程自动停止或重启；完成代码后提供清晰的重启和验收步骤。

## 5. 实施顺序与提交边界

按以下顺序推进，每个切片保持可编译、可回滚：

```text
Slice 0 测试基线
  ↓
Slice 1 文档页面 + 管理查询下沉
  ↓
Slice 2 数据与投影一致性
  ↓
Slice 3 Principal / ACL / Reader
  ↓
Slice 4 HTTP / Security / Frontend
  ↓
Slice 5 Hybrid
  ↓
Slice 6 Evaluation / Connector 下沉
  ↓
Slice 7 全量验收与 README
```

不进行大爆炸式目录重排。每次只移动一个职责并立即补测试，避免一边修缺陷一边制造无法定位的新问题。

## 6. 第一阶段最终验收门槛

必须同时满足：

- 文档管理页面首次进入无错误，筛选和分页正确；
- 跨空间相同 externalId 不串数据；
- A→B→A、并发写入和投影逆序均保持正确活动修订；
- ES/Milvus 不返回旧修订、已归档或已撤权知识；
- Admin 与 Reader 的空间和操作权限符合 ACL；
- Keyword + Vector Hybrid 在 acceptance Profile 中真实工作；
- Evidence、Citation、Trace 和 requestId 一致；
- Evaluation 和 Obsidian 基础链路可运行；
- Control Plane 无 JDBC、无具体 Connector 构造；
- Maven、前端和真实 Adapter 测试全部有明确结果；
- README、状态文档与实际实现一致；
- Graph、Wiki、Office 解析等未实现能力不被宣传为已完成。

