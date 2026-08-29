# Infinity Knowledge Runtime

Infinity Knowledge Runtime 是面向企业 Agent 的多租户知识基础设施。它把文档、
外部知识源、Wiki 页面和知识图谱转换为有来源的 Evidence，并通过 HTTP API 和
轻量 Java Client 提供 RAG 能力。系统不负责 Agent 的任务循环，也不直接生成
最终业务答案。

## 当前能力

当前分支 `codex/p1-p2-knowledge-layer` 已实现 P1 可靠性整改和 P2 知识层核心：

```text
OIDC / Tenant / Principal / Space ACL
  -> Markdown / TXT / HTML / PDF / DOCX / Obsidian
  -> Normalize / Parser Registry / Element / Chunk / immutable Revision
  -> original content + contextualText + SourceSpan / original source
  -> PostgreSQL + Elasticsearch + Milvus + Neo4j projections
  -> Keyword + Vector + Graph + Published Wiki retrieval
  -> explicit QueryContext + optional Query Planner / Multi-query
  -> ACL + active-revision guard + RRF + model rerank
  -> Evidence / Citation / Trace / Metrics / Audit
  -> Agent HTTP API / Java KnowledgeSearchTool / Console / Evaluation
```

已实现的主要能力：

- API-first 的空间、ACL、文档、修订、生命周期和投影管理；
- Keycloak/OIDC、多租户、用户/角色/部门/租户 ACL；
- Markdown、TXT、HTML、PDF、DOCX 解析，受预算约束的文件上传，以及 MinIO
  原文件保留、授权下载和安全内联预览；
- 可选 `parser-docling` 通过固定 Docling Serve Schema 契约提供 PDF 第二种 Parser；
  DOCX 具有独立开关和源表格覆盖门禁，当前固定部署默认关闭；失败、partial、Schema
  漂移、引用损坏或覆盖不足都会明确终止，不静默回退；
- Markdown API、文件上传和 Obsidian Connector 复用统一摄取主线；Parser Registry
  统一媒体类型、扩展名和 Parser 契约，拒绝已声明媒体类型与扩展名冲突；
- V17 空间文档处理配置在创建 Space 时与空间、ACL 和上传 Connector 一起固化，创建后只读；
  服务端同时固化 Pipeline、Normalizer、逐格式 Parser、Cleaner、Chunker/Tokenizer 的实际实现
  合同和总指纹，控制台只读展示并校验当前部署是否仍匹配。同名实现升级或下线后，旧 Space
  已有数据仍可查询、已有投影仍按固化合同完成，但使用 Space 固化配置的正式抽取会在读取文件前
  明确拒绝；完整且当前可执行的 TEST_ONLY 本次测试配置仍可独立运行，正式使用当前实现必须创建
  新 Space 并重新摄取；
- Chunker Provider Registry 以稳定 ID、实现版本、部署可用性和 Parser 能力前置条件驱动选择，
  后端拒绝未安装、不可用或 Parser/Chunker 能力不兼容的组合；
- 内置 `STRUCTURAL` 确定性结构 Baseline，以及可选的 `SEMANTIC_REFINEMENT`
  双向 Embedding 边界细化 Provider；语义阶段既能在低相似的可调整位置新增断点，也能在
  高相似位置删除 Baseline 软断点，但不能跨越标题、表格、代码等结构硬边界。阶段受输入规模、
  12 秒总时限和单线程有界执行器约束，失败时明确终止，不在相同处理版本下静默
  伪装成 Baseline；当前不提供绕过结构与引用约束的纯语义模式；
- Space 可统一配置最小/目标/最大 Token 预算、Overlap 和 Token Counter；当前内置
  `UTF8_BYTE_BUDGET` 以 UTF-8 字节数作为确定性预算代理，能力目录明确返回
  `exactModelTokens=false`；可选 `tokenizer-huggingface` 从本地固定
  `tokenizer.json + SHA-256` 加载精确计数，并要求运维将它固定绑定到
  Embedding Profile；真实 Serving 配对仍必须通过 Golden 验收；
- 生产摄取与试验复用无存储副作用的 `ExtractionEngine`；控制台 Space“数据抽取”页
  同时支持多文件 `TEST_ONLY` 与正式 `INGEST` Run、同 Space 单活、协作式取消、
  不覆盖的 externalId/SHA-256 判重、成功 Document/Revision 输出、逐文件状态/失败码、
  Parse/Clean/Chunk 耗时与数量、Clean 去向/原因码、固定 16 项 Chunk 边界诊断，
  有界 Element/Chunk 正文与 SourceSpan/Artifact 范围预览，以及保留在 OSS 的授权原件下载；
- 测试广场可选择受版本控制的 Golden Dataset 和同 Space Baseline，使用真实
  `ObservationFactory + AcceptanceRunner` 输出五项硬门禁，并明确区分 Run 成功与 Gate 通过；
- 抽取调度规则集中在独立 `knowledge-jobs` 模块，Runtime 只暴露一次性
  `pollOnce()`；任务并发采用数据库原子领取、进程级 `workerId` 和心跳，不引入
  Fence/Lease Token 对象；
- `knowledge-evaluation` 已提供基于 UTF-16 SourceRange 标签的抽取验收 Runner 和
  首批 Markdown/TXT Golden Dataset，硬门禁覆盖解析、Token 溢出、SourceSpan、来源核算
  与静默截断；
- Chunk 将原始 `content` 与用于向量化的 `contextualText` 分离，并把 Element 内
  UTF-16 字符范围和可选页码作为 `SourceSpan` 贯穿 PostgreSQL、ES、Milvus、
  Evidence、HTTP API 与 Java Client；
- PostgreSQL 权威事实、不可变修订、历史修订查看、A -> B -> A 恢复；
- Elasticsearch BM25、Milvus/GLM `embedding-3`、Neo4j Graph 和已发布 Wiki
  页面四类召回，统一 ACL/活动修订过滤、Deadline 与 RRF；
- Knowledge Runtime 保持无会话状态；调用方可以显式提交有界会话摘要和最近轮次，
  可选智谱 Query Planner 生成独立查询/同义变体，原查询始终保留且总候选预算不会
  随变体数成倍放大；
- GLM 在线规划、Space 排序和 Coverage 默认使用 `glm-5.2`，可切换 `glm-5.1`；
  启用这些模型阶段时必须同时启用智谱远程 Prompt Token 计数。计数模型直接取生成请求，
  不存在可独立修改的 tokenizerModel；该接口没有字符 offset，绝不进入 Chunk Tokenizer 列表。
  三个检索短任务显式关闭 GLM 深度思考，计数或预算校验失败时不再发送生成请求；
- 可选 Embedding Cosine 或智谱专用 `/rerank` 模型精排；模型成功时 Evidence
  `relevance` 使用本次查询内的最终模型分数，失败/超时回退 RRF 顺序；
- Projection heartbeat、fencing token、dirty/requeue、退避、死信和重建；
- Neo4j Entity/Relation/Event/Provenance、Graph 投影/检索和管理图探索；
- Wiki 编译、来源引用、`DRAFT -> IN_REVIEW -> PUBLISHED -> ARCHIVED` 治理，
  已发布页面通过原始活动 Chunk 提供 Evidence；
- Obsidian 白名单同步、持久化租约恢复、完整快照 manifest 和删除/移动对账；
- Retrieval Evaluation Dataset/Case/Run、Recall@K、MRR、nDCG、基线对比和阈值门禁；
- 有界异步队列、Connector/Evaluation 可恢复租约、低基数业务指标和变更审计；
- React 管理控制台覆盖空间、文档/原文件/修订、检索、Graph、Wiki、Connector、
  Evaluation、Trace 和 Audit；
- OpenAPI 契约、单 Worker Playwright 用例，以及纯 Java `knowledge-agent-client`。

当前分支已在 2026-08-22 完成数据抽取阶段的本地受控验收：真实 PostgreSQL/MinIO、
固定 Docling、正式多文件 INGEST、重复与冲突保护、Elasticsearch 关键词投影、抽取测试
广场和本地精确 Tokenizer 两态均已验证。这不等于检索、Wiki、Graph 的本轮增量或生产
HA、容量、灾备验收完成。已执行证据、延期门禁和明确未支持能力见
[P1/P2 状态](docs/P1-P2-STATUS.md) 与 [测试报告](TEST-REPORT.md)。

## 明确边界

当前仍未提供：

- Excel、PPT、图片 OCR 和通用网页爬取；
- Docling OCR/BBox/原生产物、typed 表格行列模型、通用页眉页脚/水印识别；真实容器
  PDF Golden 已通过，固定 v1.20.0 的 DOCX 表格缺失会被 fail-closed 门禁拒绝，尚未开放；
- HuggingFace 精确 Tokenizer 默认关闭，部署仍需提供与目标 Embedding 模型完全匹配、
  固定 SHA-256 的本地 `tokenizer.json`；表格/代码/日志高级策略和父子/相邻 Chunk
  自动扩展仍未实现；
- 第三方 Chunker Adapter、动态插件安装和 Provider-specific options Schema；当前只有
  Adapter-ready 的运行时/选择链与两个内置 Provider；
- 正式多文件 `INGEST` 已具备不可变配置快照、重复短路、事务发布、Document/Revision
  输出、执行前实现合同校验、发布事务指纹栅栏和发布窗口取消保护；正式处理配置或同名实现
  版本需要变化时创建新 Space、重新摄取并由调用方切换 `spaceId`，不在原 Space 内实现
  历史实现路由、影子重处理或配置提升；极小上传窗口的 OSS orphan
  reconciliation 尚未闭环；
- Query Decomposition、领域词典、变体级 Trace/评测指标和独立 Planner 执行隔离；
- Java `knowledge-agent-client` 的 Query Context 请求模型和 Console Context 输入表单；
- Graph-only Evidence 的精确 `SourceSpan`；Neo4j 当前只保存 Chunk 级 Provenance；
- 用户指定历史修订的 Agent/RAG 检索；当前历史修订只支持管理查看；
- Owner、有效期、保密等级和组织级审核策略的完整治理模型；
- Wiki Claim/Link/Diff/回滚、来源影响分析和自动增量重编译；
- Entity/Relation 标注集与 Graph 质量指标；
- 第二个真实 Connector、定时源发现和通用 Connector 市场；
- 最终答案生成及其忠实度/引用完整性评测；
- 配额/限流、备份恢复演练、跨区域 HA、完整 SLO/告警和跨服务 OTel 导出。

## 技术基线

| 能力 | 技术 |
|---|---|
| 服务 | Java 21 / Spring Boot 4.1 / Maven 3.9 Wrapper |
| 身份 | Keycloak 26 / OIDC JWT |
| 权威事实 | PostgreSQL 17 / Flyway V1-V24 |
| 关键词 | PostgreSQL fallback / Elasticsearch 9 |
| 向量 | Milvus 2.6 / GLM `embedding-3` 2048 维 |
| Graph | Neo4j / 可选 GLM 关系抽取 |
| 原文件 | MinIO |
| 前端 | React 19 / TypeScript / Vite |
| 观测 | Micrometer / Prometheus / tenant-bound audit |

## 模块

| 模块 | 职责 |
|---|---|
| `knowledge-domain` | 文档、身份、Evidence、Graph、Wiki 和 Audit 领域模型 |
| `knowledge-spi` | 写入、治理、检索、投影、Graph、Wiki、对象存储和审计端口 |
| `knowledge-runtime` | 无状态查询运行时、不可变配置快照，以及 TEST_ONLY/INGEST 抽取任务处理能力 |
| `knowledge-ingestion` | ExtractionEngine、Parser/Cleaner、确定性/语义 Chunk 和来源范围 |
| `parser-docling` | Docling Serve PDF Parser、受独立门禁保护的 DOCX Parser 与严格 lossless JSON 映射 |
| `tokenizer-huggingface` | 本地固定 tokenizer.json 的模型精确 Token 计数 |
| `knowledge-compiler` | 可追溯 Wiki 编译 |
| `knowledge-evaluation` | 检索评测、抽取 Golden Dataset 与基线门禁 |
| `connector-obsidian` | Obsidian Vault Provider |
| `provider-zhipu` | GLM Embedding、Query Planner、Prompt Token 计数、专用 Rerank、Graph/Wiki 生成适配器 |
| `store-postgres` | 权威事实、治理、任务、Wiki、评测、Trace 和 Audit |
| `store-elasticsearch` | BM25 投影/检索 |
| `store-milvus` | Vector 投影/检索 |
| `store-neo4j` | Graph 投影/检索 |
| `store-minio` | 原文件对象存储 |
| `knowledge-jobs` | Spring 后台触发规则；只调用业务模块的一次性有界动作 |
| `control-plane` | HTTP、安全、用例编排和 Composition Root |
| `knowledge-agent-client` | Agent 侧 Java HTTP Client / Tool |
| `knowledge-console` | 可视化管理、评测和 Playwright 契约 |

依赖方向保持 `domain -> spi -> runtime/adapter -> control-plane`；领域层不依赖
Spring 或厂商 SDK，应用服务不直接执行 SQL。`control-plane.application` 与
`control-plane.api` 已按 `ingestion`、`retrieval`、`connector`、`evaluation`、
`wiki`、`graph`、`governance`、`projection`、`audit`、`common` 等业务能力分包，避免 HTTP DTO、用例编排、
后台 Worker 和治理服务继续堆在同一级目录。HTTP 路径和 JSON 契约不因本次包迁移改变；
只有直接 import 控制面内部 DTO/Controller 的 Java 代码需要更新包名。详见
[总体架构](docs/ARCHITECTURE.md)。

## 本地身份

| 类型 | 值 |
|---|---|
| Keycloak | `http://localhost:8180` |
| Realm | `infinity-knowledge` |
| Console Client | `infinity-knowledge-console` |
| CLI Client | `infinity-knowledge-cli` |
| API Audience | `infinity-knowledge-api` |
| Admin | `demo-admin / demo-admin` |
| Reader | `demo-reader / demo-reader` |

这些账号只用于本地验收。`keycloak-config` 是幂等配置任务，退出码 0 后显示
`Exited` 属于正常完成。

## 运行

当前是预发布破坏式 schema 收口：V17 固化 Space 文档处理配置，V18 保存统一抽取任务、
SourceAsset、配置快照、诊断、预览与正式发布结果；V19～V24 增加 Space 检索配置、
分层 Observation 存储、请求索引、指标终止原因、执行快照和检索阶段失败事实。
旧数据库不支持原地升级。已有开发环境需要保留 MinIO/OSS 原件，清空 PostgreSQL
派生数据并从 V1～V24 重建 schema，同时按部署范围清理 Elasticsearch、Milvus、Neo4j
外部投影，随后从原件重新摄取。

轻量标准模式只要求 PostgreSQL 和 Keycloak；原文件上传还需要 `object` Profile：

```powershell
docker compose --profile identity --profile object up -d --wait postgres keycloak minio
docker compose --profile identity run --rm keycloak-config
.\mvnw.cmd -T1 -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar
```

完整受控验收启用 ES、Milvus、MinIO、Neo4j 和 GLM。首次或低资源机器应逐项
确认容器健康，避免同时反复重建索引：

```powershell
docker compose --profile acceptance up -d --wait --wait-timeout 300 `
  postgres keycloak etcd minio milvus elasticsearch neo4j
docker compose --profile acceptance run --rm keycloak-config

if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY)) {
  throw 'ZHIPU_API_KEY is required'
}
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'
$env:KNOWLEDGE_FEEDBACK_PLANNER_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_FEEDBACK_PLANNER_PROXY_PORT = '7890'
$env:KNOWLEDGE_ZHIPU_TOKENIZER_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_ZHIPU_TOKENIZER_PROXY_PORT = '7890'
$env:KNOWLEDGE_RERANKER_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_RERANKER_PROXY_PORT = '7890'
$env:KNOWLEDGE_GRAPH_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_GRAPH_PROXY_PORT = '7890'

.\mvnw.cmd -T1 -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=acceptance
```

Wiki 默认使用确定性抽取式编译；只有显式设置
`KNOWLEDGE_WIKI_GENERATIVE_ENABLED=true` 才调用 GLM。

标准 Profile 默认关闭语义切分、模型 Query Planner 和模型 Reranker。Acceptance
Profile 默认开启这三项，因此会实际消耗 GLM 配额；开发时应使用定向测试和本地
Fixture，不要为普通代码修改反复启动 Acceptance Profile。

管理控制台：

```powershell
Set-Location knowledge-console
npm.cmd install
Copy-Item .env.example .env.local
npm.cmd run dev
```

Obsidian 必须配置 API 进程可访问的真实目录白名单：

```powershell
$env:KNOWLEDGE_OBSIDIAN_ALLOWED_ROOTS = 'C:\Knowledge,C:\TeamVaults'
```

## 验收

开发阶段遵循低 CPU 策略：`-T1`、模块化测试、不批量调用 GLM、不反复执行
`clean`。收敛后执行一次完整门禁：

```powershell
.\mvnw.cmd -T1 verify

Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
npm.cmd run e2e:list
# 服务已启动且浏览器已安装时再执行 npm.cmd run e2e
```

2026-08-11 已完成 P1/P2 历史基线的单线程 Maven、核心 Adapter、Admin/Reader 浏览器和
Obsidian 对账。2026-08-22 又对最终工作树的数据抽取阶段完成受控验收，包括 V1～V18、
MinIO、Docling、Parser/Tokenizer 目录、TEST_ONLY/INGEST、重复/冲突保护、KEYWORD 投影
和抽取工作台 E2E 4/4。语义模型质量调优、检索、Wiki、Graph 及生产化门禁不能从上述结果
外推。2026-08-23 已复验 Space 创建即固化配置的代码与 PostgreSQL 事务门禁；当前 8 个
浏览器场景中的新增配置闭环仍需在专用验收环境整套重跑。API 示例见
[API](docs/API.md)，完整证据见 [测试报告](TEST-REPORT.md)，长期回归清单见
[TEST-CASES](TEST-CASES.md)，源码交付说明见 [DELIVERY](DELIVERY.md)。
