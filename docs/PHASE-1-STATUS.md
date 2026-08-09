# Phase 1 状态

> 状态日期：2026-08-09  
> 当前结论：整改代码已进入最终合并验证阶段；不是生产 HA 版本。

## 1. Phase 1 验收定义

Phase 1 交付一个可治理、可追溯、可评测的企业 RAG 基线：

```text
Identity / Tenant / ACL
  → Markdown / Obsidian
  → Revision / Chunk / Projection
  → PostgreSQL + ES + Milvus
  → Retrieval / Evidence / Trace
  → Agent API + Console + Evaluation
```

它不以 Graph、Wiki、Office 解析或生产级高可用为验收前提。

## 2. 当前已实现

### 2.1 身份、租户与治理

- Keycloak/OIDC Resource Server；
- `infinity-knowledge-api` Audience 校验；
- `tenant_id`、`sub`、Realm Role、部门声明；
- 首次认证 Principal 的幂等入驻，以及停用租户/主体拒绝；
- `USER`、`ROLE`、`DEPARTMENT`、`TENANT` 空间 ACL；
- `ALL`、`ONLY(documentIds)`、`DENY_ALL` 显式 `AccessScope`；
- Reader 可访问空间接口和前端导航隔离；
- 受信 Client 白名单约束 `system_principal`。

### 2.2 文档和投影一致性

- 空间级 API Connector：`api-upload:<spaceId>`；
- 文档 Source Key：
  `tenant + space + connector + externalId`；
- Flyway V5 迁移和组合唯一约束；
- Flyway V7 将不可变修订唯一性扩展为完整指纹：`contentHash + mediaType +
  language + processorVersion`；处理器版本包含 Parser、Chunker 和 Chunk 预算，
  Markdown media type 和语言均先规范化，因而大小写变体不会产生伪修订，
  真正的语言或处理契约变化也不会复用旧 Chunk；
- 相同活动内容幂等，A→B→A 重新激活历史修订；
- 同正文的标题、来源 URI、权威等级或元数据变化会复用修订并重新发布投影；
- 已归档文档以相同正文重新发布时恢复为 ACTIVE；
- 事务内行锁分配修订号；
- Projection Job、活动修订切换和写入保持事务一致；
- `ActiveRevisionGuard` 阻止旧 ES/Milvus 任务发布并过滤旧检索结果；
- 按空间重新排队当前活动修订的 Keyword/Vector 投影；
- Flyway V6 限制同一 Connector 的并发 RUNNING Run。

### 2.3 RAG Runtime

- Markdown 结构解析、标题感知切分和稳定 Chunk；
- PostgreSQL 关键词检索；
- Elasticsearch CJK/BM25 与索引内租户/空间过滤；
- GLM `embedding-3` Provider 和 Milvus Vector Index；
- 并行 Retriever、RRF、Evidence、Citation、Sufficient 和 Warning；
- `language`、`sourceType` Filter 校验和 PostgreSQL/ES 执行；Milvus 当前
  明确返回不支持 Warning，不静默放宽；
- Runtime 的 Tenant/Space/Document/Active Revision 二次校验；
- 稳定 HTTP DTO，不暴露领域 Value Object 序列化；
- `X-Request-Id` 在所有响应 Header 中稳定返回，并在应用层检索响应、
  `ApiError`、Trace 和 MDC 中复用；安全过滤链直接产生的 401/403 仅保证 Header。

### 2.4 管理、连接器与评测

- 管理 Overview、空间、文档分页/筛选、Chunk、Connector 和 Trace 查询；
- 当前活动修订的文档投影状态/死信重试，以及空间级 rebuild；
- ACL list/grant/revoke；
- Obsidian `SourceConnectorProvider`、实路径白名单和异步全量同步；
- Connector Run 持久状态查询、前端轮询及刷新恢复；
- Evaluation Dataset、Case、异步 Run、Recall@K、MRR、nDCG；
- `KnowledgeAdministrationStore`、`KnowledgeGovernanceStore`、
  `EvaluationStore`、`ConnectorStateStore`；
- Control Plane Application 层不执行 SQL，也不直接构造具体 Obsidian Connector；
- React 管理控制台覆盖空间/ACL、文档/Chunk、活动投影重试、检索、Trace、
  Connector 和评测；异步重试与 Run 状态按文档/数据集隔离。

### 2.5 验收运行配置

`application-acceptance.yml` 明确启用：

- Elasticsearch Keyword；
- Milvus Vector；
- GLM Embedding；
- 本地 `127.0.0.1:7890` 代理默认值；
- Strict Hybrid 启动探测和 fail-fast。

Keycloak 幂等配置任务会同步 Console、CLI、API Client、Audience Mapper、
角色、测试用户、密码和部门声明，适用于已有数据卷。

## 3. 验证证据

以下是本轮较早整改子任务已经实际产生的历史分片证据，不等同于合并后全量
门禁，也不包含随后加入的 V7 与最新前端改动：

| 范围 | 已执行结果 |
|---|---|
| PostgreSQL 文档/投影/管理/评测/迁移（V7 前分片） | 17 个 IT 通过，0 失败 |
| PostgreSQL 治理/ACL | 2 个 IT 通过，0 失败 |
| Elasticsearch Adapter | 2 个真实 IT 通过，0 失败 |
| Milvus Adapter | 2 个真实 IT 通过；同组 1 个条件测试跳过 |
| Runtime/Control Plane 分片单元测试 | 相关整改切片通过 |
| 前端 | 较早切片的 `npm run lint`、`tsc -b` 通过；最终 UI 改动尚未复跑，Vite 打包未完成 |
| Keycloak/Acceptance 配置 | JSON、Compose 和配置契约分片校验通过 |

说明：

- GLM 真实 API IT 的现存报告为条件跳过，不能据此宣称本轮已重新调用 GLM；
- Connector 新增 Run Polling、OIDC Audience、最新 AccessScope 与前端合并发生在
  部分分片验证之后；
- 修订输入模型简化、同正文投影元数据/归档恢复、Overview 投影统计、配置化
  Source URI 白名单、异步错误契约及 V7 完整修订指纹发生在后端分片验证之后；
- 因此合并后的 `clean verify`、最新前端 Build 和浏览器/API 冒烟必须由根验收
  任务重新执行后，才可以把 Phase 1 标为最终 `VERIFIED`。

## 4. 最终验收待执行

### 后端

```powershell
.\mvnw.cmd clean verify
```

默认命令会按环境条件跳过外部 IT。启动 `acceptance` Compose 后，需要显式启用
并分别记录以下外部契约：

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

### 前端

```powershell
Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
```

### 运行验收

使用 `acceptance` Profile 验证：

1. Admin/Reader Authorization Code + PKCE；
2. Token Audience 和必需 Claim；
3. 创建空间、ACL grant/revoke 和 Reader 可见空间；
4. Markdown 写入、A→B→A、投影完成与空间 rebuild；
5. 精确关键词、语义表达和混合 Evidence；
6. Connector 创建、同步、Run 轮询；
7. Evaluation Dataset/Case/Run；
8. 文档页面无过滤加载、分页、Trace 和 requestId。

验收用例见根目录 [TEST-CASES.md](../TEST-CASES.md)。

## 5. 当前不支持

以下内容未实现，不能出现在 Phase 1“已完成”清单中：

- Neo4j Entity/Relation 投影、Graph Retrieval、GraphRAG；
- LLM Wiki Compiler、知识页面审核和差异发布；
- PDF/DOCX/HTML/Excel/PPT 解析；
- 知识原文件和附件的 MinIO 存储；
- Obsidian 删除/移动对账和分布式定时调度；
- Connector/Evaluation 多实例租约恢复；
- 投影租约续期与提交围栏、RUNNING 期间元数据变更重排、自动历史回填；
- 生成答案的忠实度、完整性和引用正确性评测；
- 生产 HA、跨区域容灾、完整 SLO/告警和 OpenTelemetry 导出。

Compose 中存在 MinIO 是因为 Milvus Standalone 依赖它，不表示系统已实现原文件
对象存储。

## 6. 验收结论规则

当前代码状态应表述为：

> **Phase 1 功能整改已实现，分片验证通过，等待合并后的全量构建与运行验收。**

只有第 4 节全部完成且没有 P0/P1 回归失败后，才可更新为：

> **Phase 1 ACCEPTED。**

该结论仍不等价于“完整企业知识库”或“生产就绪”。
