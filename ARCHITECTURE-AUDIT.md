# Infinity Knowledge Runtime 架构审计报告

> 审计日期：2026-08-09  
> 审计范围：Phase 1 缺陷整改后的共享源码  
> 目标：检查模块边界、数据一致性、安全治理、扩展性和验收边界

## 1. 总体判断

项目已经从“带管理页面的 RAG 原型”收敛为结构清晰的 Phase 1 企业知识运行
时基线：

- Domain/SPI/Runtime/Adapter 依赖方向合理；
- PostgreSQL 是文档、修订、ACL 和任务状态事实源；
- Elasticsearch/Milvus 被明确视为可重建投影；
- Control Plane Application 不再直接执行 SQL；
- Connector、Evaluation、治理和管理查询都有轻量用例端口；
- Agent API 返回 Evidence/Citation，而不是裸 Chunk；
- 多租户 ACL、活动修订、Audience 和 requestId 已形成代码闭环；
- 管理控制台覆盖治理、检索、连接器和评测。

当前适合定位为：

> **Phase 1 企业 RAG 基线，等待合并后的全量验收。**

当前仍不适合定位为：

> **完整生产级企业知识库、GraphRAG 平台或高可用任务系统。**

## 2. 架构边界

```text
HTTP Controller / JWT Adapter
           │
           ▼
  Application Service
  permission + orchestration
           │
           ▼
     Domain / SPI Ports
    ┌──────┼────────┬──────────┐
    ▼      ▼        ▼          ▼
Postgres   ES     Milvus   Connector Provider
```

当前关键规则：

1. `knowledge-domain` 不依赖 Spring、数据库和厂商 SDK；
2. `knowledge-spi` 不暴露 JDBC/ES/Milvus 类型；
3. `control-plane/application` 不出现 SQL/JDBC；
4. Composition Root 负责适配器实例化；
5. PostgreSQL 是事实源，外部索引必须可重建；
6. Tenant、ACL 和 Active Revision 同时在索引请求与 Runtime 校验；
7. 不为每张表创建 Repository，不引入通用 Command/Event/Mapper 框架。

这套边界保持了原工程风格，也避免了为修复缺陷引入过度封装。

## 3. 原审计项整改状态

状态：

- `RESOLVED`：Phase 1 所需实现已完成；
- `PARTIAL`：已有可用基线，但生产级可靠性仍有缺口；
- `OPEN`：仍是后续架构工作。

| ID | 原问题 | 状态 | 当前处理/剩余工作 |
|---|---|---|---|
| A-001 | 活动修订不是所有通道统一权威 | `RESOLVED` | `ActiveRevisionGuard` 用于 Worker、ES/Milvus 结果和 Runtime 二次校验 |
| A-002 | ES 旧任务可逆序覆盖新修订 | `RESOLVED` | 旧任务发布前判定过期，移除危险反向删除；真实 ES IT 分片通过 |
| A-003 | 文档身份与空间边界不一致 | `RESOLVED` | 空间级 API Connector、完整 Source Key、V5 迁移/约束 |
| A-004 | 回滚状态机和并发修订不安全 | `RESOLVED` | A→B→A/归档恢复、完整修订指纹、版本/时间单调、同正文投影元数据更新、事务内行锁分配修订号 |
| A-005 | ACL 不可运营 | `RESOLVED` | Principal 入驻、ACL list/grant/revoke、可访问空间、Reader UI、显式 AccessScope |
| A-006 | 投影租约无续期/提交围栏 | `OPEN` | 仍需 heartbeat/fencing token；同修订元数据在 Job RUNNING 期间变化时也可能漏掉重投影，当前仅有发布前活动修订保护 |
| A-007 | Generation 原子发布停留在设计 | `OPEN` | 当前以活动修订守卫和可重建投影保证正确性，未实现全索引 Alias 原子切换 |
| A-008 | Adapter 后启无历史回填 | `PARTIAL` | 已有按空间 rebuild API/UI；缺少自动扫描、进度和调度 |
| A-009 | 跨通道 Filter 语义不一致 | `RESOLVED` | HTTP 只允许 `language`/`sourceType`；PG、ES、Milvus 均执行精确匹配，Milvus 使用独立 metadata-v2 collection schema |
| A-010 | Control Plane 越过 Port 直接持久化 | `RESOLVED` | 四个用例 Store Port；Application Service 无 SQL/JDBC |
| A-011 | Connector SPI 被具体实现绕过 | `RESOLVED` | `SourceConnectorProvider` + Map 选择，无额外 Registry 框架 |
| A-012 | 检索无端到端 Deadline/取消/熔断 | `OPEN` | 有有界线程池/通道超时，尚无统一 Deadline、取消传播和熔断 |
| A-013 | JWT 未绑定 API Audience | `RESOLVED` | API Client、Audience Mapper、Resource Server Audience 校验 |
| A-014 | Evaluation/Connector 无进程恢复 | `PARTIAL` | 独立有界 Worker、Connector single-flight/Run polling；重启恢复未实现 |
| A-015 | HTTP 契约与领域对象耦合 | `RESOLVED` | `KnowledgeQueryResponse` 固定标量和 `generatedAt`；仍可补 OpenAPI 生成 |
| A-016 | Obsidian 未校验真实路径 | `RESOLVED` | Root/文件 `toRealPath`、拒绝未批准链接；Windows Junction 运行复测待执行 |

## 4. 关键整改评审

### 4.1 Control Plane 简化

整改使用四个按用例聚合的端口：

```text
KnowledgeAdministrationStore
KnowledgeGovernanceStore
EvaluationStore
ConnectorStateStore
```

这是合理粒度：

- 没有为每张表创建接口；
- 没有通用 `BaseRepository<T>`；
- HTTP DTO、JDBC 类型没有泄露到端口；
- Evaluation 和 Connector 只拆分其天然异步 Worker；
- `SourceConnectorProvider` 只包含类型和打开 Connector 的必要能力。

后续不应继续把这些端口拆成大量 CRUD Repository，除非出现独立生命周期和
清晰的跨适配器需求。

### 4.2 文档/投影一致性

正确性链路目前为：

```text
完整 Source Key
  → 正文 Hash + Media Type + Language + Processor Version
  → 不可变 Revision
  → 事务发布 Active Revision + Projection Job
  → Worker ActiveRevisionGuard
  → ES/Milvus 可重建投影
  → Retrieval ActiveRevisionGuard
```

该方案在不引入分布式事务的前提下解决了 Phase 1 最严重的错误知识风险。
生产阶段仍需要租约续期/围栏、可观测自动回填和归档/删除全通道对账。

### 4.3 治理和 Reader

Keycloak 只承担认证和声明，业务库承担授权事实：

- 首次已认证请求幂等创建 Principal；
- 已停用 Tenant/Principal 不会被 upsert 重新激活；
- ACL 支持 User/Role/Department/Tenant；
- Reader 查询可访问空间，不访问 Admin API；
- Runtime 使用 `ALL`/`ONLY`/`DENY_ALL`，空集合不再产生歧义。

生产环境还应增加 ACL 变更审计、双人审批（若业务需要）和跨索引传播 SLO。

### 4.4 Connector 扩展

当前新增外部软件的最小路径是：

1. 实现 `SourceConnectorProvider`；
2. 返回实现 `SourceConnector` 的 Adapter；
3. 增加该来源的定义校验和配置 API/DTO；
4. 在 Composition Root 注册 Bean；
5. 复用 `ConnectorStateStore`、Run 和摄取流程。

Provider 已解耦同步执行核心，但当前配置入口仍是 Obsidian 专用端点。后续接入
Notion、Confluence、SharePoint 时仍需新增各自配置契约；无需先建设插件市场
或通用脚本运行时。Webhook/增量游标可在明确需求后扩展端口。

### 4.5 Evaluation

检索评测已是独立应用能力，而不是 Controller 内 SQL：

- Dataset/Case/Run 持久化在 PostgreSQL Adapter；
- Runner 计算 Recall@K、MRR、nDCG；
- Case 失败隔离并保留 Trace；
- 控制台可管理和查看结果。

当前仍只评测 Retrieval，不评测最终生成答案的 Faithfulness/Citation
Correctness；这应在 Agent 或 Generation 层接入后单独建模。

## 5. 剩余 P1 风险

### 5.1 任务可靠性

- Projection lease 没有 heartbeat/fencing；
- 同一活动修订的标题、来源或权威元数据若在 Job 已 RUNNING 后变化，旧快照可能
  完成发布且此次重排请求被唯一 Job 吞掉；需用 dirty/requeue 标记或 fencing 解决；
- Connector/Evaluation 是进程内 Worker，重启后不会自动接管 RUNNING；
- rebuild 为人工按空间触发，没有历史扫描计划和进度资源；
- 删除/归档/移动/ACL 变化尚无完整跨存储对账作业。

建议下一阶段优先做一个统一但简单的租约任务模式，而不是引入大型工作流
引擎。只有流程复杂度确实增长后再评估 Temporal/Camunda。

### 5.2 检索运行控制

- 缺少单请求统一 Deadline；
- Future 取消未完整传播到 Provider；
- 外部通道没有熔断和隔离舱指标；
- RRF 后没有实际 Reranker Provider；
- 混合 Filter 仍需完整 ES/Milvus/PG 契约矩阵。

### 5.3 可观测与运维

后续需要有界基数业务指标：

- Projection queue depth、oldest age、dead count；
- Retriever latency/failure/timeout/degrade；
- Connector/Evaluation success/failure/duration；
- Evidence insufficient rate 和通道命中率；
- rebuild backlog/progress。

指标标签不能使用 documentId、userId、traceId 等高基数字段。

## 6. 明确规划边界

| 能力 | 当前状态 | 建议阶段 |
|---|---|---|
| Neo4j / Graph Retrieval | 未实现 | Graph 阶段 |
| Entity/Relation Provenance | 未实现 | Graph 阶段 |
| LLM Wiki Compiler | 未实现 | Wiki 阶段 |
| PDF/DOCX/HTML/Office Parser | 未实现 | Connector/Parser 阶段 |
| 知识原文件 MinIO | 未实现 | Raw Source 阶段 |
| 生成质量评测 | 未实现 | Agent/Generation 阶段 |
| Connector 分布式调度 | 未实现 | Production Reliability |
| Evaluation 进程恢复 | 未实现 | Production Reliability |
| 多区域 HA/容灾 | 未实现 | Production Deployment |

Compose 中的 MinIO 是 Milvus Standalone 依赖，不能据此标记“原文件存储已完成”。

## 7. 推荐后续切片

### Slice A：完成 Phase 1 验收

- 合并后 Maven/前端全量门禁；
- Admin/Reader 浏览器矩阵；
- Strict Hybrid + GLM/ES/Milvus 运行测试；
- F-001～F-018 缺陷回归；
- A-001～A-016 中 `RESOLVED` 项执行正向回归，`OPEN`/`PARTIAL` 项只验证已记录
  的降级与 `NOT_SUPPORTED` 边界，不将其误判为已经实现；

### Slice B：生产任务可靠性

- lease heartbeat/fencing；
- Connector/Evaluation 恢复；
- rebuild 进度和自动回填；
- 删除/归档/ACL 对账；
- 业务指标和告警。

### Slice C：RAG 质量

- 真实 Reranker；
- Filter 契约矩阵；
- Query Rewrite/Multi-query（基于评测证明收益后再加）；
- 生成层忠实度和引用评测。

### Slice D：新知识表示

- Office/HTML Parser 与原文件存储；
- Neo4j Entity/Relation/Provenance；
- Graph Retrieval；
- Wiki Compiler、人工审核和差异发布。

Graph 和 Wiki 不应在 Phase 1 尾声并行混入缺陷修复。

## 8. Phase 1 架构验收门槛

- `control-plane/application` 无 SQL/JDBC 和具体 Connector 构造；
- 跨空间 externalId、A→B→A、并发修订、ES/Milvus 旧修订测试通过；
- Admin/Reader、ACL grant/revoke、Audience、必需 Claim 测试通过；
- Strict Hybrid 在依赖缺失时 fail-fast，在依赖可用时真实工作；
- 文档页面无过滤、过滤组合和分页测试通过；
- Stable DTO、Citation、Trace、requestId 契约通过；
- Connector Run、Evaluation Run 基础成功/失败路径通过；
- README、API、状态与源码一致；
- Graph/Wiki/Office/MinIO/生产 HA 明确标记未实现。

满足这些门槛可以验收 Phase 1，但仍不能据此宣称生产 HA 或完整知识 OS。
