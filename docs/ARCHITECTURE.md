# Infinity Knowledge Runtime 总体架构

## 1. 系统定位

Infinity Knowledge Runtime 为 Agent 提供可信的企业知识上下文。它负责知识
摄取、治理、检索、Evidence、评测和观测，不负责 Agent 的计划循环、业务
工作流或最终答案生成。

```text
Agent / Console / Application
             │ HTTP + OIDC JWT
             ▼
        Control Plane
             │
      Application Services
             │
       Domain / SPI Ports
       ├────────┬─────────┬──────────┐
       ▼        ▼         ▼          ▼
 PostgreSQL  Elastic    Milvus   Connector Provider
 fact store   BM25      vector       Obsidian
```

Phase 1 的完整数据路径是：

```text
Source → Document/Revision → Element/Chunk → Projection Jobs
       → PostgreSQL/ES/Milvus → AccessScope → RRF
       → Evidence/Citation → Trace → Agent API/Evaluation
```

Neo4j Graph 和 LLM Wiki 是后续知识表示，不属于当前运行链路。

## 2. 模块与依赖方向

```text
knowledge-domain
       ▲
knowledge-spi ◄──── knowledge-evaluation
       ▲
knowledge-runtime   knowledge-ingestion
       ▲                    ▲
store-*          connector-obsidian / provider-zhipu
       ▲                    ▲
       └────── control-plane ──────┘

knowledge-console → HTTP API
```

边界约束：

- `knowledge-domain` 为纯 Java，不依赖 Spring 或厂商 SDK；
- `knowledge-spi` 定义运行时所需端口，不暴露 JDBC/ES/Milvus 类型；
- `control-plane` 的 Controller 只处理 HTTP/JWT/DTO；
- Application Service 负责权限检查与用例编排，不执行 SQL；
- PostgreSQL SQL 只存在于 `store-postgres`；
- 具体 Connector 由 `SourceConnectorProvider` 创建，应用层不直接
  `new ObsidianVaultConnector(...)`；
- Composition Root 可以实例化适配器并把它们注入端口。

为避免 Repository 过度拆分，管理和异步业务采用四个按用例聚合的端口：

| 端口 | 责任 |
|---|---|
| `KnowledgeAdministrationStore` | Overview、空间、文档、Chunk、Connector、Trace 读模型 |
| `KnowledgeGovernanceStore` | Principal、空间和 ACL |
| `EvaluationStore` | Dataset、Case、Run 和 Case Result |
| `ConnectorStateStore` | Connector Definition、Checkpoint 和 Sync Run |

## 3. 写入与一致性

### 3.1 文档事实

PostgreSQL 是 Document、Revision、Element、Chunk、ACL 和任务状态的事实源。
文档身份由以下 Source Key 唯一确定：

```text
tenantId + spaceId + connectorId + externalId
```

API 上传连接器使用 `api-upload:<spaceId>`，因此不同空间的相同
`externalId` 不会复用同一文档。Flyway V5 迁移旧数据并建立相应组合约束。

修订采用不可变内容与处理契约：

- 与当前活动修订完整指纹相同：幂等返回；
- 命中历史非活动完整指纹：重新激活该历史修订；
- 内容、规范化语言或 Parser/Chunker 处理契约变化：在文档行锁/事务内分配
  新修订号并发布；
- 完整指纹由正文 Hash、规范化 media type/language 和 processor version 组成；
- 活动修订切换、Chunk 和 Projection Job 写入处于同一事务；
- A→B→A 和并发写入不会使用事务外 `MAX(revision)+1`。

### 3.2 外部投影

Elasticsearch 和 Milvus 是可重建投影，不是事实源：

```text
PostgreSQL transaction
  ├── revision/chunk changes
  └── projection_job
            │ lease + retry + dead-letter
            ▼
       Projection Worker
            │ ActiveRevisionGuard
       ┌────┴────┐
       ▼         ▼
      ES       Milvus
```

Worker 在发布前检查目标修订仍为当前活动修订。适配器和检索入口还会通过
`ActiveRevisionGuard` 批量过滤结果，避免旧任务乱序完成或旧向量返回过期
知识。按空间的 rebuild API 会为全部活动修订重新排队当前已启用的外部通道，
用于 Adapter 后启或运维重建。

当前仍没有跨存储分布式事务、投影租约续期/提交围栏和自动历史回填调度；
同一修订元数据在 Job 已 RUNNING 后变化时，也需要后续 dirty/requeue 或 fencing
机制保证最终再次投影。这些属于生产可靠性后续项。

## 4. 读取链路

1. Spring Resource Server 校验 Issuer、签名、有效期和
   `infinity-knowledge-api` Audience；
2. `JwtPrincipalContextFactory` 从已验证 Token 读取 `tenant_id`、`sub`、
   Realm Role 和部门，普通请求字段不能覆盖身份；
3. `PrincipalProvisioningFilter` 幂等登记首次登录主体；已停用租户/主体拒绝；
4. `AccessPolicy` 把数据库 ACL 编译为显式 `AccessScope`：
   `ALL`、`ONLY(documentIds)` 或 `DENY_ALL`；
5. Query Analyzer 只接受 `language`、`sourceType` Filter；PostgreSQL/ES 执行
   这些过滤，当前 Milvus 通道会明确拒绝并产生降级 Warning，而不是静默放宽；
6. 已配置 Retriever 并行召回，RRF 去重并融合；
7. Runtime 再次校验 Tenant、Space、Document Scope 和活动修订；
8. Evidence Builder 返回稳定 Citation，Trace 记录步骤与耗时。

Retriever 不可用时，标准模式可返回通道 Warning；`acceptance` 严格混合模式
会在启动时探测 ES、Milvus 和 GLM，关键通道缺失时直接失败。

## 5. 多租户和 ACL

权限事实保存在 PostgreSQL，Keycloak 负责认证和声明：

```text
JWT tenant/sub/roles/departments
              │
              ▼
     Principal + Space ACL
   USER / ROLE / DEPARTMENT / TENANT
              │
              ▼
     Accessible Spaces / AccessScope
              │
              ▼
   index-side filter + runtime verification
```

约束：

- 每个持久对象、检索投影和任务都携带 `tenantId`；
- 管理查询和治理端口都以当前 Principal/Tenant 为输入；
- `knowledge-admin` 可创建空间和维护 ACL；
- Reader 使用 `/api/v1/spaces/accessible`，不依赖管理员空间列表；
- 未授权空间不会进入检索请求，越权候选也不会进入 Evidence；
- `system_principal=true` 只有受配置 Client 才能生效。

ACL grant/revoke 会立即改变后续查询生成的 AccessScope。当前不宣称 ACL
变更与所有外部投影字段之间存在跨存储原子更新。

## 6. 管理、评测和连接器

### 管理控制台

控制台提供：

- Dashboard 与租户管理读模型；
- 空间创建、ACL grant/revoke、按空间投影 rebuild；
- 文档筛选、分页、Chunk 和投影状态；
- Retrieval Lab、Evidence、Citation、Warning 和 Trace；
- Connector 配置、同步、Run 轮询；
- Evaluation Dataset/Case/Run 和指标。

Reader 只显示允许的检索导航；未知路由和渲染错误有显式恢复页面。

### Evaluation

`EvaluationApplicationService` 通过 `EvaluationStore` 编排持久化数据，实际
执行使用独立有界线程池。当前支持 Recall@K、MRR、nDCG 和 Case 级错误隔离。
进程中断后的租约恢复尚未实现。

### Connector

`ConnectorApplicationService` 通过 `ConnectorStateStore` 和
`SourceConnectorProvider` 工作。V6 数据库约束确保同一租户/Connector 只有
一个 RUNNING Run，控制台按 `runId` 轮询终态并可在刷新后恢复展示。

Obsidian 允许根和文件使用真实路径校验，不跟随未批准的符号链接。当前只支持
全量快照；文件删除/移动对账、定时调度和多实例任务恢复尚未实现。

## 7. 数据职责

| 组件 | Phase 1 职责 |
|---|---|
| PostgreSQL | 权威元数据、修订、Chunk、ACL、任务、治理读模型、评测和 Trace |
| Elasticsearch | 可重建的 BM25/精确词投影 |
| Milvus | 可重建的 Chunk Embedding 投影 |
| GLM | 摄取投影和语义查询 Embedding |
| Keycloak | 本地 OIDC、测试角色/用户、API Audience |
| MinIO | 仅作为 Milvus Compose 依赖；不是知识原文件存储 |
| Neo4j | 未接入 |

## 8. 安全与观测

- 业务 API 除 Health 外全部要求 Bearer JWT；
- Token 必须含 API Audience；缺少身份 Claim 返回 401；
- 来源 URI 使用配置化协议白名单，默认允许 `http`、`https`、`obsidian`；
  前端仍独立限制可点击协议；
- `X-Request-Id` 在入口规范化一次，所有响应 Header 均返回；进入应用层后还
  贯穿 `ApiError`、Evidence、Trace 和 MDC，安全链直接 401/403 不承诺 JSON 错误体；
- Trace 保存查询 Hash、ID、候选数、耗时和 Warning，不保存 Query/Chunk 正文；
- API Key 和 Token 不进入普通日志。

业务指标、OpenTelemetry 跨服务导出、生产级审计事件和 HA 告警仍需后续补齐。

## 9. Phase 1 边界

Phase 1 的验收目标是“安全、可追溯、可评测的混合检索基础设施”，不是完整
企业知识操作系统。以下内容保持 `NOT_SUPPORTED`：

- Graph/Neo4j 与 GraphRAG；
- Wiki/Page 编译、审核与知识图谱抽取；
- PDF/DOCX/HTML/Office 解析；
- 原文件/附件 MinIO 存储；
- 生成答案质量评测；
- 多实例调度恢复、跨区域容灾、零停机迁移等生产 HA。

这些边界不得通过预留的模型、Docker 依赖或文档目标描述成已实现能力。
