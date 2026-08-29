# Infinity Knowledge Runtime 总体架构

## 1. 定位与边界

Infinity Knowledge Runtime 为企业 Agent 提供可治理、可追溯、可评测的知识
上下文。它负责摄取、知识表示、投影、检索、Evidence、评测和观测；Agent 的
计划循环、业务工作流和最终答案生成不在本系统内。

```text
Agent Client / Console / Application
                 | HTTP + OIDC JWT
                 v
     Control Plane (HTTP / Auth / Composition) ---\
                                                   > Knowledge Runtime / Extraction Engine
     knowledge-jobs (thin scheduled triggers) ----/                    |
                                                              Domain / SPI Ports
       /        |        |        |        \
PostgreSQL  Elastic   Milvus    Neo4j     MinIO
fact/tasks    BM25     vector    graph   source assets
```

核心数据路径：

```text
Markdown / Multi-file upload / Rich file / Obsidian
  -> immutable SourceAsset in MinIO + PostgreSQL Run/Item
  -> Normalize -> Parse -> Clean -> Chunk
  -> TEST_ONLY diagnostics/gates OR transactional Document/Revision publication
  -> transactional Projection Jobs
  -> PostgreSQL / Elasticsearch / Milvus / Neo4j
  -> Keyword + Vector + Graph + Published Wiki retrieval
  -> ACL + active-revision guard + deadline + RRF + rerank
  -> Evidence / Citation / Trace
  -> Agent API / Java Tool / Evaluation / Console
```

## 2. 模块和依赖方向

```text
control-plane -> knowledge-runtime -> knowledge-ingestion / knowledge-evaluation
control-plane -> knowledge-compiler / knowledge-spi / store-* / provider-*（仅装配）
knowledge-jobs -> runtime 一次性任务动作（Spring 定时入站适配器）
parser-docling -> knowledge-ingestion
tokenizer-huggingface -> knowledge-ingestion
knowledge-spi -> knowledge-domain

knowledge-agent-client ---> HTTP API
knowledge-console -------> HTTP API
```

约束：

- `knowledge-domain` 是纯 Java，不依赖 Spring、JDBC 或厂商 SDK；
- `knowledge-spi` 只暴露领域端口，不泄露 ES/Milvus/Neo4j/MinIO 类型；
- Controller 只处理 HTTP、JWT、验证和 DTO；Application Service 编排用例；
- SQL 只存在于 `store-postgres`；具体存储由 Composition Root 注入；
- `@Scheduled` 只存在于 `knowledge-jobs`；Runtime 不创建 Spring Scheduler 或长期线程；
- Connector 通过 `SourceConnectorProvider` 注册，不由应用层直接构造；
- 不为每张表建立 Repository，按用例聚合端口以维持可读性。

## 3. 权威事实与一致性

### 3.1 文档和修订

PostgreSQL 是空间、文档、修订、Element、Chunk、ACL、任务和治理状态的权威
事实源。文档 Source Key 为：

```text
tenantId + spaceId + connectorId + externalId
```

修订是不可变的。完整指纹包含规范化正文 Hash、media type、language 和
processor version。相同活动指纹幂等；命中历史指纹时重新激活；真正内容或处理
契约变化时在文档行锁事务内创建新修订。文档、修订、Chunk、Source Object
引用和 Projection Job 共同提交。

管理 API 可以查看所有修订和指定修订的 Chunk。`ACTIVE`、`ARCHIVED`、
`DELETED` 使用乐观版本做可逆状态转换；普通检索只接受活动文档，管理面可以
在授权后查看保留的历史事实和原文件。

### 3.2 原文件

多文件任务先建立接收意图，再逐文件计算 SHA-256、写入 MinIO，并把不可猜测的
SourceAsset 引用登记到 PostgreSQL。系统不向 HTTP 客户端暴露 Bucket/Object Key，
只返回授权后的元数据或流。失败、取消、重复和冲突都保留原件，不做补偿删除；进程在
对象写入与 SourceAsset 登记之间硬崩溃仍可能留下极小窗口的孤儿对象，当前没有后台
orphan reconciler。

解析预算限制源文件大小、批次总量、解压后大小、页面数、Element 数、文本长度、归档
条目和压缩比。当前解析 TXT/Markdown、HTML、PDF 和 DOCX；可选 Docling 提供 PDF 第二
实现，DOCX 在固定部署的表格覆盖 Golden 未通过时 fail-closed 并保持禁用。不支持
Excel/PPT/图片 OCR。

### 3.3 可重建投影

```text
PostgreSQL transaction
  + revision/chunk/source changes
  + projection_job (PENDING/RETRY/RUNNING/...)
                  |
          lease + token + heartbeat
                  v
          Projection Worker
        /          |          \
      ES         Milvus      Neo4j
```

Flyway V8 为投影租约增加 fencing token 与 dirty/requeue。Worker 只有持有当前、
未过期 Token 才能 heartbeat、complete 或 fail；RUNNING 期间再次标脏会在本轮
结束后回到待处理状态。发布前和读取后均执行 `ActiveRevisionGuard`，过期修订
不能成为 Evidence。系统仍不提供跨 PostgreSQL/外部存储的分布式事务，物理旧
记录允许延迟清理，但逻辑读取必须立即拒绝。

## 4. 检索运行时

1. Resource Server 校验 Issuer、签名、有效期和 API Audience；
2. 只从 JWT 构造 Tenant、Principal、Role、Department；
3. `AccessPolicy` 编译 `ALL`、`ONLY(documentIds)` 或 `DENY_ALL`；
4. 确定性 Query Analyzer 规范化查询，并按关系型词标记 Graph 通道；
5. Keyword、Vector、Graph 和 Published Wiki Retriever 在有界线程池中并行；
6. 每个请求有绝对 Deadline，每个通道有超时，取消/饱和使用稳定失败语义；
7. 各通道执行 tenant/space/document/sourceType/language 约束；
8. Runtime 再做 ACL 和活动修订守卫；
9. RRF 融合，可选 `CosineEmbeddingReranker` 对受预算候选重排；
10. Evidence Builder 返回原始 Chunk Citation，Trace 记录步骤、计数和耗时。

标准模式允许单通道降级并返回 Warning；`acceptance` Profile 对配置的 ES、Milvus、
Neo4j 和 GLM 依赖执行更严格装配/探测。可选 Query Planner 支持保留原查询的 Rewrite/
Multi-query；当前没有 Query Decomposition、多跳迭代或父子/相邻 Chunk 扩展。

## 5. Graph 知识层

Graph 领域模型包含 Entity、Event、Relation 和 Provenance。Neo4j 节点/边都
携带 tenant、space、document、revision/chunk 来源；查询使用固定 Cypher 模板，
不把用户文本拼接成 Cypher。Graph 投影在活动修订范围内幂等更新，Retriever
限制最大 hops 和结果数，并把关系映射回有来源的 Evidence。

实体关系可由 GLM 抽取；Graph 管理 API 和控制台用于 tenant/ACL 范围内探索。
当前没有 Entity/Relation 人工标注集、社区摘要和 Graph 质量指标。

## 6. Wiki 知识层

Wiki 编译以已授权的 Document/Revision/Chunk 为显式来源：

```text
source chunks -> deterministic or optional GLM compiler -> immutable page revision
             -> DRAFT -> IN_REVIEW -> PUBLISHED -> ARCHIVED
```

页面保存 source coverage、content hash、compiler version 和来源引用；状态转换
使用 `expectedVersion` 防止覆盖更新。只有 PUBLISHED 页面进入 PAGE Retriever，
Retriever 返回页面关联的原始活动 Chunk，而不是把未经验证的生成文本冒充事实。

当前没有 Claim/Link/Diff/回滚、来源变更影响分析或自动增量重编译。

## 7. 异步任务、调度与 Connector 对账

所有 Spring 定时触发集中在 `knowledge-jobs`，它只调用各业务模块的一次性有界动作。
多文件 `TEST_ONLY/INGEST` 使用 PostgreSQL 原子领取、进程级 workerId 和 heartbeat，
不引入 Lease/Fence Token 对象；同 Space 只允许一个活动 Run。Space 创建时固化用户配置以及
Pipeline、Normalizer、逐格式 Parser、Cleaner、Chunker/Tokenizer 的实际实现合同。新 Run
在保存原件前校验，Worker 在任何 Item 前复核；INGEST 发布事务再以合同指纹执行最终写栅栏。
投影代际只从 Space 已存合同派生，不要求当前部署仍安装旧 Adapter。TEST_ONLY 只生成诊断、
预览和 Gate，INGEST 在发布窗口原子写入正式事实并投递 Projection Job。排队/运行任务可协作
取消，进入短暂 PUBLISHING 事务窗口后拒绝不安全取消。

Connector/Evaluation 保留其已有的可恢复租约语义：Run 先持久化为 `PENDING`，Coordinator
周期恢复待处理或过期任务，旧执行者无权提交。该机制不泄漏到多文件抽取主线。

Obsidian 每次成功完整扫描产生 Snapshot Manifest。只有仍持有租约且本轮完整
成功时才原子提升 Manifest，并将上一成功快照中缺失的文档归档；失败/部分扫描
不触发删除。移动按“旧 externalId 归档、新 externalId 创建”处理。当前没有
定时源发现和第二个真实 Connector。

## 8. 多租户、安全与观测

- 每个事实、投影、任务、Wiki 页面、Graph 节点和审计事件都携带 tenant；
- ACL 支持 `USER`、`ROLE`、`DEPARTMENT`、`TENANT`；
- Reader 使用可访问空间 API，Admin 才能使用管理/治理端点；
- `system_principal=true` 仅对受信 Client 生效；
- 来源 URI、Obsidian 实路径和内联原文件类型均使用白名单；
- `X-Request-Id` 贯穿响应、错误、MDC、Trace；正文、Token、API Key 不进普通日志；
- `MeteredTraceSink` 输出有界标签的检索时延/结果指标；
- `MutationAuditFilter` 只记录变更请求的 route pattern、主体、状态和耗时，不记录
  Body、Token、查询文本或具体资源 URI；审计查询按 tenant 分页。

## 9. 存储职责

| 组件 | 职责 |
|---|---|
| PostgreSQL | 权威事实、ACL、SourceAsset、抽取/摄取任务、修订、Chunk、Wiki、评测、Trace、Audit |
| Elasticsearch | 可重建 BM25/精确词投影 |
| Milvus | 可重建 Chunk Embedding 投影 |
| Neo4j | 可重建关系投影和 Graph traversal |
| MinIO | 不可变原文件保留；失败、取消、重复和冲突后仍可按权限下载 |
| GLM | Embedding；可选 Graph/Wiki 生成 |
| Keycloak | OIDC、Audience、测试角色和用户 |

## 10. 当前工程边界

实现边界、验证证据和待验收项以 [P1/P2 状态](P1-P2-STATUS.md) 为准。当前不
宣称生产 HA；备份恢复、配额、容量/SLO、跨区域容灾和跨服务 OpenTelemetry
导出仍需独立生产化阶段。
