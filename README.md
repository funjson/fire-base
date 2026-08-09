# Infinity Knowledge Runtime

Infinity Knowledge Runtime 是面向企业 Agent 的多租户知识基础设施。它把企业
知识源转换为可检索、可追溯、可评测的 Evidence，并通过稳定 HTTP API 提供
RAG 能力；它不负责 Agent 的任务循环，也不在当前阶段生成最终答案。

## Phase 1 交付范围

当前源码已经形成以下纵向闭环：

```text
OIDC 身份
  → Tenant / Principal / Space ACL
  → Markdown 或 Obsidian 摄取
  → Document / Revision / Element / Chunk
  → PostgreSQL + Elasticsearch + Milvus 投影
  → ACL 过滤 + Active Revision Guard
  → RRF 融合 + Evidence / Citation + Trace
  → Agent API / 管理控制台 / 检索评测
```

Phase 1 已实现：

- API-first 的知识空间、Markdown 文档和投影管理；
- 多租户、用户/角色/部门/租户 ACL，以及 Reader 可访问空间接口；
- PostgreSQL 事务元数据、不可变修订、A→B→A 历史修订恢复和空间级文档身份；
- V7 完整修订指纹：规范化正文 Hash、媒体类型、规范化语言与处理器版本共同
  决定修订；处理器版本覆盖 Parser、Chunker 及其 Chunk 预算；
- Elasticsearch CJK/BM25 与 Milvus/GLM `embedding-3` 检索通道；
- 旧修订投影保护、检索后活动修订校验、按空间重建外部投影；
- Obsidian Provider、Vault 实路径白名单、新增/修改的异步 upsert 和 Run 状态轮询；
- Retrieval Dataset/Case/Run、Recall@K、MRR、nDCG；
- 稳定检索 HTTP DTO、全响应 Header 统一的 `X-Request-Id`、应用层关联和不记录正文的 Trace；
- React 管理控制台：空间/ACL、文档/Chunk、活动修订投影任务与 DEAD 重试、
  检索、Trace、连接器，以及可配置 Top K 和逐案例结果的评测管理。

当前明确不支持：

- Neo4j Graph Retrieval；
- LLM Wiki Compiler；
- PDF、DOCX、HTML、Excel、PPT 等 Office/富文档解析；
- 面向知识原文件的 MinIO 对象存储（Compose 中 MinIO 仅供 Milvus 使用）；
- 跨区域 HA 和完整生产运维方案；
- Obsidian 文件移动/删除对账，以及 Connector/Evaluation 进程重启恢复；
- 生成答案的忠实度/引用评测。

详细状态见 [Phase 1 状态](docs/PHASE-1-STATUS.md)、[测试报告](TEST-REPORT.md)
和 [架构审计](ARCHITECTURE-AUDIT.md)。

## 技术基线

| 项目 | 当前值 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| 构建 | Maven Wrapper 3.9.11 |
| 元数据/治理 | PostgreSQL 17 |
| 关键词检索 | PostgreSQL 或 Elasticsearch 9 |
| 向量检索 | Milvus 2.6 |
| Embedding | 智谱 `embedding-3`，2048 维 |
| 身份 | Keycloak 26 / OIDC JWT |
| 前端 | React 19 + TypeScript + Vite |

## 模块

| 模块 | 职责 |
|---|---|
| `knowledge-domain` | 文档、修订、Chunk、身份、查询、Evidence 和 Trace 领域模型 |
| `knowledge-spi` | 摄取、治理、管理查询、连接器、索引、检索和 Trace 端口 |
| `knowledge-runtime` | 查询分析、并行召回、ACL 二次校验、RRF 和 Evidence 构建 |
| `knowledge-evaluation` | 检索评测模型、指标与持久化端口 |
| `knowledge-ingestion` | Markdown 结构解析和标题感知切分 |
| `connector-obsidian` | Obsidian Vault 连接器与 Provider |
| `provider-zhipu` | 智谱 Embedding API 适配器 |
| `store-postgres` | 事务事实源、治理、管理读模型、任务、评测与 Trace |
| `store-elasticsearch` | 关键词投影和 BM25 检索 |
| `store-milvus` | 向量投影和语义检索 |
| `control-plane` | HTTP、安全、应用用例编排和依赖装配 |
| `knowledge-console` | 可视化管理和评测控制台 |

应用层通过少量按用例聚合的端口访问 PostgreSQL：
`KnowledgeAdministrationStore`、`KnowledgeGovernanceStore`、
`EvaluationStore` 和 `ConnectorStateStore`。`control-plane/application`
不包含 SQL，也不直接构造 Obsidian 连接器。详见
[总体架构](docs/ARCHITECTURE.md)。

## 本地身份

本地验收账号仅用于开发：

| 类型 | 值 |
|---|---|
| Keycloak | `http://localhost:8180` |
| Realm | `infinity-knowledge` |
| Console Client | `infinity-knowledge-console` |
| CLI Client | `infinity-knowledge-cli` |
| API Audience | `infinity-knowledge-api` |
| 管理员 | `demo-admin / demo-admin` |
| Reader | `demo-reader / demo-reader` |

`keycloak-config` 是幂等配置任务。它会在已有数据卷中同步 Client、Audience
Mapper、角色、测试用户和部门声明；执行完成后显示 `Exited (0)` 是正常状态。

## 运行方式

### 1. 标准开发模式

标准模式使用 PostgreSQL 关键词通道，外部检索通道默认关闭：

```powershell
docker compose --profile identity up -d --wait --wait-timeout 300 `
  postgres keycloak
docker compose --profile identity run --rm keycloak-config
.\mvnw.cmd -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar
```

### 2. Phase 1 严格混合验收模式

`acceptance` Profile 同时启用 PostgreSQL、Keycloak、Elasticsearch、Milvus
和 GLM Embedding，并在应用启动时探测关键依赖。缺少 API Key、ES、Milvus
或 Embedding 能力时会启动失败，不会静默降级为 PostgreSQL-only。

```powershell
docker compose --profile acceptance up -d --wait --wait-timeout 300 `
  postgres keycloak etcd minio milvus elasticsearch
docker compose --profile acceptance run --rm keycloak-config

if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY)) {
  throw 'ZHIPU_API_KEY is required for the acceptance profile'
}
# 默认使用 127.0.0.1:7890；无代理时显式覆盖。
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'

.\mvnw.cmd -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=acceptance
```

默认资源服务要求 Token 的 `aud` 包含 `infinity-knowledge-api`。只有
`KNOWLEDGE_TRUSTED_SYSTEM_CLIENTS` 中显式列出的 Client 才能使用
`system_principal=true`。

### 3. 管理控制台

```powershell
Set-Location knowledge-console
npm.cmd install
Copy-Item .env.example .env.local
npm.cmd run dev
```

控制台固定使用 `http://localhost:5173`；`localhost` 和
`127.0.0.1:5173` 都已加入本地 OIDC/CORS 配置。Reader 只显示检索相关
导航，管理员可使用治理、文档、连接器、评测和 Trace 页面。

### 4. Obsidian 白名单

Obsidian 读取的是 API 进程所在机器上的目录，必须先配置白名单：

```powershell
$env:KNOWLEDGE_OBSIDIAN_ALLOWED_ROOTS = 'C:\Knowledge,C:\TeamVaults'
```

允许根和目标文件均按真实路径校验；越界 Junction/符号链接不会被当作合法
Vault 内容读取。

API 与 Connector 写入的 `sourceUri` 使用可配置协议白名单，默认允许
`http,https,obsidian`。接入 Confluence 等新来源时，可显式扩展：

```powershell
$env:KNOWLEDGE_ALLOWED_SOURCE_SCHEMES = 'http,https,obsidian,confluence'
```

管理控制台仍只把自身批准的安全协议渲染为可点击链接；允许存储新的来源协议
不会自动扩大浏览器跳转白名单。

## 验收与测试

默认后端构建门禁：

```powershell
.\mvnw.cmd clean verify
```

默认命令会按测试上的环境条件跳过真实 PostgreSQL、Elasticsearch、Milvus 和
GLM 合约测试。启动 `acceptance` Compose 后，可显式执行完整外部契约：

```powershell
$env:RUN_POSTGRES_TESTS = 'true'
$env:ELASTICSEARCH_IT_URI = 'http://localhost:9200'
$env:MILVUS_IT_URI = 'http://localhost:19530'
$env:RUN_ZHIPU_TESTS = 'true'
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'
if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY)) {
  throw 'ZHIPU_API_KEY is required for GLM integration tests'
}
.\mvnw.cmd clean verify
```

GLM 与 Milvus+GLM 的真实 IT 会读取上述代理变量；不需要代理时将 Host 设为空
字符串或不设置这两个变量。

前端门禁：

```powershell
Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
```

外部 Adapter 集成测试通过环境变量显式启用，详细命令和当前验证证据见
[Phase 1 状态](docs/PHASE-1-STATUS.md)。本轮各修复子任务已分别验证
PostgreSQL、Elasticsearch、Milvus；较早前端切片的 `lint` 与 TypeScript 编译
曾通过，最终 UI 改动尚未复跑，Vite 生产打包也未完成。合并后的全量 Maven、前端 Build 与
浏览器验收仍应由最终验收任务重新执行，不能用分片结果替代；V7 完整修订
指纹及其后续合并改动当前也尚未获得新的全量执行证据。

API 调用、空间 ACL、投影重建、Connector Run 和评测示例见
[API 验收说明](docs/API.md)，长期回归用例见 [TEST-CASES.md](TEST-CASES.md)。
