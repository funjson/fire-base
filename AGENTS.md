# Infinity Knowledge Runtime 工程约束

本文适用于仓库全部模块。代码评审、人工开发和自动化 Agent 都必须遵守。

## 1. 语言与注释

- 业务代码的类、公共接口、领域对象、复杂算法和非显然约束必须使用中文注释或中文 Javadoc。
- 第三方协议字段、标准术语和异常代码保留原始英文；注释中应补充中文语义。
- 注释重点解释“为什么、边界和不变量”，不要逐行复述代码。
- 日志、Trace 和异常不得记录 Token、原始文档正文、用户完整查询或模型原始响应。

## 2. 结构与可读性

- 保持实现简洁并遵守 SOLID；优先清晰的组合、值对象和小型接口，禁止为“可能的未来”预建复杂框架。
- 一个类只承担一个稳定职责。过长方法先按业务阶段提取私有方法；只有存在独立变化原因时才提取新类型。
- 同一能力只能有一条权威业务链路。Markdown、文件上传、Connector 等入口应复用相同的规范化、解析、
  Chunk、写入和投影逻辑，不得复制一套近似实现。
- Domain 与 SPI 不依赖 Spring、数据库、HTTP 客户端或具体厂商 SDK；Adapter 负责技术细节。
- 模块或包不得形成“杂物箱”。当一个包同时包含 API DTO、应用服务、后台 Worker、异常、映射器等多个
  关注点，或生产类明显膨胀影响浏览时，按业务能力分包，而不是继续把文件放在同一级目录。

推荐的 `control-plane` 内部结构：

```text
api/{document,retrieval,connector,evaluation,wiki,graph,audit}
application/{ingestion,retrieval,connector,evaluation,wiki,graph,governance}
config/{ingestion,retrieval,storage,security,async}
```

存储模块按聚合或端口分包，例如：

```text
store-postgres/{document,projection,connector,evaluation,wiki,trace,audit,common}
```

分包只改变组织时不得顺带重写业务语义；使用编译、聚焦测试和契约测试逐批迁移。

## 3. 企业级摄入与 Chunk

- 摄入主线统一为：Source -> Normalize -> Parse -> Clean -> Chunk -> Write -> Projection Job。
- 富文档优先通过 Docling Adapter 获得结构和 Provenance；内部领域模型不得直接暴露第三方对象。
- Chunk 必须支持结构边界、模型 Token 硬上限、标题上下文化、SourceSpan 和按内容类型选择规则。
- 表格、代码、日志、列表不得直接复用普通段落的硬切逻辑。
- Semantic Chunking 是可配置策略，不得替代确定性 Baseline；必须有模型预算、超时和版本指纹。
  模型失败时不得在同一语义版本下静默产出 Baseline 结果，应明确失败并由运维显式切换策略后重试。
- Chunk 策略、Tokenizer、清洗、Overlap 和上下文化版本都必须进入 processorVersion，变化后创建新修订并重投影。
- 原文展示内容与用于检索的 contextualText 分离；引用始终回到原始 SourceSpan。

## 4. 企业级检索

- Knowledge Runtime 保持无会话状态；上下文只能由调用方在本次请求中显式传递。
- 原查询永远参与召回。Query Rewrite、Multi-query 或 Decomposition 不得删除错误码、接口名、制度编号等精确词。
- Query Planner 和模型 Reranker 必须使用结构化输出、有限候选、硬超时、有界并发、稳定降级和可观测 Trace。
- 模型只能规划查询和候选排序，不能修改 tenant、space、ACL 或服务端授权过滤。
- Rerank 必须输出可解释的排序分数/原因码并写入评测结果；模型失败时回退到 RRF 顺序。
- 所有改进必须通过同一 Dataset 的 Baseline/Candidate 对比验证 Recall、MRR、nDCG、引用准确率、延迟和成本。

## 5. 验证与资源约束

- 开发阶段使用 Maven `-T1`、聚焦模块与聚焦测试；不要反复运行全仓 `clean verify`。
- 不在日常开发中执行高并发压测、全库重建、批量 GLM 调用或长时间高 CPU 任务。
- 外部 PostgreSQL、ES、Milvus、Neo4j、MinIO、Docling 和模型契约在受控验收阶段集中运行。
- 结构迁移、包迁移和功能修改分开提交/验证，避免大范围机械移动掩盖业务变更。
