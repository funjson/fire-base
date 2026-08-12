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
  -> Document / immutable Revision / Element / Chunk / original source
  -> PostgreSQL + Elasticsearch + Milvus + Neo4j projections
  -> Keyword + Vector + Graph + Published Wiki retrieval
  -> ACL + active-revision guard + RRF + embedding rerank
  -> Evidence / Citation / Trace / Metrics / Audit
  -> Agent HTTP API / Java KnowledgeSearchTool / Console / Evaluation
```

已实现的主要能力：

- API-first 的空间、ACL、文档、修订、生命周期和投影管理；
- Keycloak/OIDC、多租户、用户/角色/部门/租户 ACL；
- Markdown、TXT、HTML、PDF、DOCX 解析，受预算约束的文件上传，以及 MinIO
  原文件保留、授权下载和安全内联预览；
- PostgreSQL 权威事实、不可变修订、历史修订查看、A -> B -> A 恢复；
- Elasticsearch BM25、Milvus/GLM `embedding-3`、Neo4j Graph 和已发布 Wiki
  页面四类召回，统一 ACL/活动修订过滤、Deadline、RRF 与可选 Embedding Rerank；
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

当前分支已完成第一阶段本地受控验收；这不等于生产 HA、容量或灾备验收完成。
已执行证据、延期门禁和明确未支持能力见
[P1/P2 状态](docs/P1-P2-STATUS.md) 与 [测试报告](TEST-REPORT.md)。

## 明确边界

当前仍未提供：

- Excel、PPT、图片 OCR 和通用网页爬取；
- Query Rewrite、Multi-query、父子/相邻 Chunk 自动扩展；
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
| 权威事实 | PostgreSQL 17 / Flyway V1-V15 |
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
| `knowledge-runtime` | 查询分析、并行召回、Deadline、守卫、RRF、Rerank 和 Evidence |
| `knowledge-ingestion` | Markdown 与富文档解析、预算和 Chunk |
| `knowledge-compiler` | 可追溯 Wiki 编译 |
| `knowledge-evaluation` | 检索评测与基线门禁 |
| `connector-obsidian` | Obsidian Vault Provider |
| `provider-zhipu` | GLM Embedding、Graph/Wiki 生成适配器 |
| `store-postgres` | 权威事实、治理、任务、Wiki、评测、Trace 和 Audit |
| `store-elasticsearch` | BM25 投影/检索 |
| `store-milvus` | Vector 投影/检索 |
| `store-neo4j` | Graph 投影/检索 |
| `store-minio` | 原文件对象存储 |
| `control-plane` | HTTP、安全、用例编排和 Composition Root |
| `knowledge-agent-client` | Agent 侧 Java HTTP Client / Tool |
| `knowledge-console` | 可视化管理、评测和 Playwright 契约 |

依赖方向保持 `domain -> spi -> runtime/adapter -> control-plane`；领域层不依赖
Spring 或厂商 SDK，应用服务不直接执行 SQL。详见
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
$env:KNOWLEDGE_GRAPH_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_GRAPH_PROXY_PORT = '7890'

.\mvnw.cmd -T1 -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=acceptance
```

Wiki 默认使用确定性抽取式编译；只有显式设置
`KNOWLEDGE_WIKI_GENERATIVE_ENABLED=true` 才调用 GLM。

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

2026-08-11 已完成最终工作树单线程 Maven 门禁、核心真实 Adapter、API/UI 冒烟、
Admin/Reader 单 Worker Playwright（2/2）和 Obsidian 删除/恢复对账。结论为
“第一阶段本地受控验收通过”，不代表生产 HA 或容量验收。API 示例见
[API](docs/API.md)，完整证据见 [测试报告](TEST-REPORT.md)，长期回归清单见
[TEST-CASES](TEST-CASES.md)，源码交付说明见 [DELIVERY](DELIVERY.md)。
