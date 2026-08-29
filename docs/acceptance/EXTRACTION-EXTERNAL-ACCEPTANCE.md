# 数据抽取外部依赖验收

本文只覆盖抽取阶段的真实 PostgreSQL、MinIO、Docling、多文件 API 与 Elasticsearch
关键词投影，不启用 Wiki、Graph、Milvus Vector 或批量模型调用。日常单元测试不能替代
本验收。

## 固定部署合同

| 能力 | 验收合同 |
| --- | --- |
| PostgreSQL | Compose `infinity-knowledge-postgres`，数据库 `infinity_knowledge`，Flyway V1～V24；抽取核心为 V17/V18，V19～V24 是同一应用必须连续迁移的检索配置与 Observation schema |
| MinIO | Compose `infinity-knowledge-minio`，只清理验收生成的随机对象 |
| Docling Serve | `ghcr.io/docling-project/docling-serve-cpu:v1.20.0@sha256:419967009a6b507bf25380132335cf8b354e6ce9df4d40f46a612de2e9ddcb88` |
| Docling JSON | `DoclingDocument@1.10.0`；服务实际返回不一致时验收必须失败 |
| Docling Java | `ai.docling:docling-serve-client:0.5.3` |
| Space 实际处理合同 | 创建时由服务端固化流程、规范化、逐格式 Parser、Cleaner、Chunker/Tokenizer 实现材料及总 SHA-256；部署漂移必须明确失败 |

Docling 使用独立 `extraction-acceptance` Profile，普通开发不会无意加载模型：

```powershell
docker compose --profile extraction-acceptance up -d --wait --wait-timeout 900 docling
```

## 数据库重建安全门禁

预发布阶段允许破坏式重建，但只能操作本仓库 Compose 创建的数据库。执行前必须同时满足：

1. 容器名为 `infinity-knowledge-postgres`；
2. Compose Project 为 `infinity-knowledge`，配置文件指向本仓库 `docker-compose.yml`；
3. Compose Service 为 `postgres`；
4. 容器内只有目标业务库 `infinity_knowledge`；
5. 当前连接的 `current_database()` 与 `current_user` 都是 `infinity_knowledge`。

本机若同时存在 `fire-search-postgres` 或其他数据库容器，禁止使用宿主机端口或模糊名称
执行清理。确认上述门禁后，仅重建目标库的 `public` schema：

```powershell
docker exec infinity-knowledge-postgres psql `
  -U infinity_knowledge -d infinity_knowledge `
  -v ON_ERROR_STOP=1 `
  -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO infinity_knowledge; GRANT ALL ON SCHEMA public TO public;'
```

随后启动 API，由应用内 Flyway 从 V1 迁移到 V24。验收结束后应核对迁移连续、全部成功，
确认 `source_asset`、`extraction_run`、`extraction_run_item` 与不可变配置快照列存在，
并确认 V19～V24 的检索配置与 Observation schema 已连续成功创建。

## 可重复外部测试

PostgreSQL Store 使用隔离的 Testcontainers 数据库，不操作开发库：

```powershell
$env:RUN_POSTGRES_TESTS = 'true'
.\mvnw.cmd -T1 -pl store-postgres -am `
  "-Dtest=PostgresSpaceDocumentProcessingConfigStoreIT,PostgresKnowledgeGovernanceStoreIT,PostgresExtractionRunStoreIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

当前选择集共 15 个真实 PostgreSQL 用例，除抽取状态外还必须覆盖治理行与配置行的同事务
回滚、并发相同创建幂等，以及不可变配置不能被第二次写入覆盖。

知识写入和发布最终栅栏需执行完整 Store 回归，不得只运行单个漂移用例：

```powershell
$env:RUN_POSTGRES_TESTS = 'true'
.\mvnw.cmd -T1 -pl store-postgres -am `
  "-Dtest=PostgresKnowledgeStoreIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

当前完整类为 21 个真实 PostgreSQL 用例，必须 21/21 且 0 跳过。

MinIO 测试使用随机 Object ID，并在 `finally` 中精确删除本次对象；不会清空 Bucket：

```powershell
$env:RUN_MINIO_TESTS = 'true'
.\mvnw.cmd -T1 -pl store-minio -am `
  "-Dtest=MinioObjectStorageIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Docling Golden 测试在内存生成最小 PDF 与带表格 DOCX，调用真实 Serve。PDF 必须完整成功；
固定 v1.20.0 的 DOCX 已知会在 lossless JSON 中丢表格，必须稳定触发
`DOCLING_SOURCE_COVERAGE_MISMATCH`，且 DOCX 独立开关保持关闭。测试不在日志中输出正文或
lossless JSON：

```powershell
$env:RUN_DOCLING_TESTS = 'true'
$env:KNOWLEDGE_DOCLING_ENDPOINT = 'http://localhost:5001'
$env:KNOWLEDGE_DOCLING_SERVER_CONTRACT = 'DoclingDocument@1.10.0'
.\mvnw.cmd -T1 -pl parser-docling -am `
  "-Dtest=DoclingServeGoldenIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 多文件 API 门禁

API 必须在默认 Profile 下连接上述 PostgreSQL、MinIO、Keycloak 和可选 Docling。使用
`demo-admin` 获取 Access Token 后，在同一 Space 中验证：

- 不依赖已有 Space 读取处理能力目录，使用返回的 `defaultConfig` 创建 Space；配置、
  创建者 ACL 与 API Connector 在同一事务中落库，创建后没有配置 PUT；
- 再次读取 Space 时必须返回服务端生成的完整 `processingContract` 和
  `runtimeContractMatched`；创建请求与 `testConfig` 均不得提交或伪造实际实现合同；
- 完全相同的创建请求幂等成功；同 ID 的不同名称、处理配置或非活动 Space 返回稳定 409，
  不覆盖旧配置也不复活旧数据；
- 一个 Multipart 请求上传至少两个不同格式的原始文件，不使用 ZIP；
- 返回一个任务和两个 Item，两个 MinIO 原件均可下载且 SHA-256 一致；
- 同一 Space 的第二个活动任务返回冲突，其他 Space 不受影响；
- 状态依次进入 `QUEUED/RUNNING` 后到达唯一终态；
- 取消只清理结构化派生/预览状态，不删除 MinIO 原件；
- 失败记录稳定原因码，不记录正文、Token、模型原始响应；
- 测试广场展示配置快照、processorVersion、Parse/Clean/Chunk 耗时、边界诊断与预览；
- `testConfig` 只覆盖本次 TEST_ONLY Run，完成后再次读取 Space 配置必须与创建值完全一致；
- 保持配置 ID 不变但替换任一实际实现材料时，使用 Space 固化配置的正式任务必须在保存
  原件前失败，已排队 Worker 在读取 OSS 前也必须整单失败，原因码为
  `PROCESSING_CONTRACT_MISMATCH`；完整且当前可执行的 TEST_ONLY 本次配置仍可独立运行；
- `TEST_ONLY` 不写正式 Document、Revision、Chunk 或 Projection Job。
- 正式 `INGEST` 首次发布生成 Document/Revision/Element/Chunk；相同来源与内容返回
  `SKIPPED_DUPLICATE` 并复用输出；相同 externalId 的不同内容返回
  `EXTERNAL_ID_CONFLICT`，不得覆盖标题、权威度或已有修订；
- 启用 KEYWORD 投影时，Projection Job 和 Elasticsearch 文档必须与 PostgreSQL 活动修订、
  title、authority 和来源身份一致。
- 正式发布事务必须在写入知识数据前同时校验固定配置版本和实际处理合同指纹；已有数据
  查询及已存在投影不依赖当前 Adapter 可用性。

## Tokenizer Serving 配对

本仓库的 HuggingFace Tokenizer 只保证固定本地 `tokenizer.json` 的精确计数。只有目标
Embedding Serving 同时提供可审计 Token ID、Token 数或同一文件摘要时，才能执行跨服务
Golden 配对。当前 Compose 没有目标 Embedding Serving，因此该项必须报告为
`NOT_CONFIGURED`，不能用本地单元测试冒充通过。上线某个模型 Profile 前，需补充中文、
英文、混合文本、Emoji、组合字符、空白、长词、特殊 Token 与最大上下文边界样本。

本轮已对固定本地 `tokenizer.json` 完成两态验收：默认未启用时能力目录可见但不可选择，
原因码为 `HUGGINGFACE_TOKENIZER_DISABLED`；显式启用固定 SHA-256 后，API、Space 创建、
真实 `TEST_ONLY` 执行和 Run 配置快照均使用同一 Tokenizer ID。该结果只证明本地 Adapter
及配置链正确，不改变目标 Embedding Serving 配对仍为 `NOT_CONFIGURED` 的结论。

## 2026-08-22 实测结果

- Flyway V1～V18 在目标空 schema 中连续执行成功，真实 PostgreSQL Store IT 7/7；
- MinIO 随机对象写入、哈希/长度/中文文件名、下载及精确清理 1/1；
- 固定 Docling 容器 PDF Golden 成功，DOCX 表格缺失按预期 fail-closed；
- 正式双文件 INGEST 首次发布、两项重复短路和 externalId 冲突三条链路通过；
- 首次发布得到 2 个 Document、2 个 Revision、3 个 Element、2 个 Chunk；重复未新增修订，
  冲突未覆盖既有数据；
- 2 个 KEYWORD Projection Job 均一次成功，Elasticsearch 2 条 Chunk 与 PostgreSQL 活动
  修订及业务身份一致；
- 抽取工作台浏览器 E2E 4/4，覆盖双文件 TEST_ONLY、精确 Tokenizer、取消，以及正式
  INGEST 的成功/重复/冲突；前端 lint、TypeScript 和生产构建通过。

上述结果是 Space 创建即固化处理配置之前的历史抽取基线；新创建契约、`testConfig` 字段和
只读页面必须完成本文件新增门禁后，才能重新标记为当前工作树通过。

## 2026-08-23 不可变配置复验

- Control Plane 全量单测 428 个通过，0 失败、0 错误，1 个外部条件测试跳过；
- 最终 Space 配置、Runtime 和 Controller 聚焦回归 21/21，处理 Pipeline 选择集另有
  30/30 通过；
- 上述 3 个 PostgreSQL IT 在 Testcontainers 中合计 15/15，治理、ACL、API Connector
  与处理配置同事务回滚，并发重复创建只留下一个完整定义；
- Console lint、TypeScript、生产构建和 8 条 E2E 清单发现通过；新增 Space 固化、请求级
  `testConfig` 与旧 PUT 405 场景尚未在整套受控服务上运行，因此当前浏览器门禁仍待验收。

## 2026-08-23 实际处理合同聚焦复验

- 合同相关 22 个测试类共 101/101；流程、规范化、Parser、Cleaner、Chunker 任一材料变化
  均改变总指纹，损坏或伪造指纹会被拒绝；
- Space 配置与 Run 快照的单元/真实 PostgreSQL 往返 14/14；发布批次边界 3/3，且真实
  PostgreSQL 已验证配置缺失、指纹不匹配都在知识表零写入时拒绝；
- `PostgresKnowledgeStoreIT` 完整回归 21/21、0 跳过；正式写入、重复/冲突和最终合同指纹
  栅栏均通过；
- Console lint、TypeScript、生产构建和 8 条 E2E 清单发现通过；只读页面已展示固化处理
  版本、总指纹和当前部署匹配状态；
- OpenAPI 静态解析为 45 paths、52 operations、101 schemas、356 refs，无缺失引用或重复
  operationId；
- 本轮没有启动完整受控服务执行 live Playwright，不能把 2026-08-22 的 4/4 历史结果
  当作当前 8 个场景已经通过。

## 通过定义

只有以下项目同时满足，才能声明抽取阶段可验收：

- V1～V24 在空库一次迁移成功，真实 PostgreSQL Store IT 通过；
- 真实 MinIO 往返、完整性校验和精确清理通过；
- 固定 Docling 镜像的 PDF 成功 Golden 通过，DOCX 表格缺失被覆盖门禁稳定拒绝，并确认
  `KNOWLEDGE_DOCLING_DOCX_PARSER_ENABLED=false` 时能力目录仍展示但不可选择；
- 多文件 API、Single-flight、取消、失败、原件保留、预览和诊断通过；
- 正式 INGEST 的发布、重复、冲突保护和已启用投影对账通过；
- 本地精确 Tokenizer 的禁用态与启用态都通过，且没有冒充 Serving 配对；
- 页面只展示真实运行数据，不把静态目标规则显示为已经通过；
- 页面展示 Space 固化的实际处理合同和当前部署匹配状态；部署漂移时正式任务、Worker 与
  发布均 fail-closed，不能静默改用同名新实现；
- 未配置的外部 Serving 配对明确标记，而不是静默降级。
