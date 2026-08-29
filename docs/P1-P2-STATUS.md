# P1/P2 企业知识层状态

> 状态日期：2026-08-23
>
> 分支：`codex/p1-p2-knowledge-layer`
>
> 当前结论：2026-08-11 的 P1/P2 历史基线继续有效；2026-08-22 已对最终工作树的
> 数据抽取阶段完成受控验收，包括 V1～V18、MinIO、Docling、Parser/Tokenizer 能力目录、
> TEST_ONLY 测试广场、正式多文件 INGEST、重复/冲突语义和 Elasticsearch 关键词投影。
> 2026-08-23 又完成 Space 创建即固化用户配置和实际处理实现合同的代码、数据库与页面
> 聚焦复验，并加入运行时漂移拦截；当前
> 8 个浏览器场景仍需在专用验收环境整体重跑。
> 检索、Wiki、Graph 的本轮增量不由这次抽取验收外推；生产化、HA 和容量门禁仍未完成。

## 1. 状态定义

- `IMPLEMENTED`：源码、契约和管理入口已存在。
- `TARGETED_VERIFIED`：只执行了相关模块的定向测试或编译检查，不代表全仓或真实组件验收。
- `ACCEPTANCE_VERIFIED`：本轮在最终工作树或真实组件上完成受控验收。
- `FINAL_ACCEPTANCE_PENDING`：实现已进入共享树，但最终工作树验收尚未执行。
- `DEFERRED`：不阻塞第一阶段代码验收，但上线前需按部署目标补充。
- `NOT_SUPPORTED`：当前没有可用实现，不得作为交付能力宣传。

`ACCEPTANCE_VERIFIED` 仅代表本地 Docker、低 CPU、单 Worker 场景，不代表生产 HA、容量或长期稳定性。
下表标为 `ACCEPTANCE_VERIFIED` 的旧能力来自 2026-08-11 历史基线；不得推导为本轮新代码也已通过。

当前仍是预发布阶段。空间处理配置历史草案已合并为最终 V17，V18 新增统一抽取任务、
SourceAsset、不可变配置快照、诊断、预览和正式发布输出。旧开发库不支持原地升级；本轮已按
授权精确重建本仓库 PostgreSQL `public` schema 并运行 V1～V18，MinIO 原件未清空。

## 2. 能力矩阵

| 能力 | 实现 | 当前验证 | 说明 |
|---|---|---|---|
| OIDC、多租户、用户/角色/部门/租户 ACL | IMPLEMENTED | ACCEPTANCE_VERIFIED | Keycloak 幂等配置、Claim 和 Admin/Reader 浏览器矩阵通过 |
| 不可变修订、A -> B -> A、活动修订守卫 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Flyway V15、Store IT 与运行链路通过；物理 GC 延后 |
| Projection heartbeat/fencing/dirty-requeue | IMPLEMENTED | ACCEPTANCE_VERIFIED | 单元/Store 回归和 E2E 实际投影通过；双实例竞争为 DEFERRED |
| 历史投影有界 overfetch 与 Graph 逐跳守卫 | IMPLEMENTED | ACCEPTANCE_VERIFIED | ES、Milvus、Neo4j 真实契约通过 |
| Deadline、通道超时、取消、有界队列 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Runtime 门禁通过；高并发容量测试为 DEFERRED |
| ES + Milvus + RRF + Embedding Rerank（8/11 基线） | IMPLEMENTED | ACCEPTANCE_VERIFIED | ES 3/3、Milvus 4 pass + 1 conditional skip、GLM 1/1，浏览器 Evidence 链路通过 |
| 统一摄取主线与 Parser Registry | IMPLEMENTED | ACCEPTANCE_VERIFIED | 正式文件只走异步多文件 Run；Markdown/Connector 与 Run 复用 ExtractionEngine/DocumentPublicationService；媒体类型与扩展名冲突会拒绝 |
| Space 文档处理配置与角色清洗（V17 最终契约） | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | Space 创建时原子物化完整配置，创建后只读；测试运行可临时覆盖但不写回。全量单测、真实 PostgreSQL 回滚/并发幂等和页面构建已通过，当前浏览器闭环待重跑 |
| Space 实际处理实现合同与漂移拦截 | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | 服务端固化流程、规范化、逐格式 Parser、Cleaner、Chunker/Tokenizer 实现材料及总指纹；正式任务、Worker 和发布写入均拒绝漂移，配置页展示固化版本及当前部署是否匹配。合同相关 101/101、PostgreSQL 往返 14/14、知识写入完整回归 21/21 和发布最终栅栏 1/1 已通过，完整 live Playwright 待重跑 |
| Chunker Provider/Token Counter Registry | IMPLEMENTED | ACCEPTANCE_VERIFIED | 页面动态展示 2 个内置 Provider、UTF8 预算 Counter、未启用的 HuggingFace Adapter；本地固定 tokenizer.json 的 exact Adapter 有独立验收，目标 Embedding Serving 配对仍为 NOT_CONFIGURED |
| 确定性 + 可选双向语义 Chunk | IMPLEMENTED | STRUCTURAL ACCEPTANCE_VERIFIED / SEMANTIC TARGETED_VERIFIED | 双向 CUT/JOIN、硬边界、Token/Span/Overlap 与 16 项诊断均有真实结构链和定向测试；本轮未调用外部语义模型，不宣称语义参数已完成企业语料调优 |
| Query Context + Planner + Multi-query | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | Runtime 无会话；HTTP/OpenAPI/Console API Client 支持摘要/最近轮次，原查询强制保留，总候选预算有界；Console 表单与 Java Agent Client 尚未接入 |
| 智谱专用 Rerank 与最终分数 | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | `/api/paas/v4/rerank`；模型分数写入 Evidence relevance，失败/超时回退 RRF，部分评分有稳定 warning |
| SourceSpan + contextualText + Flyway V16 | IMPLEMENTED | EXTRACTION ACCEPTANCE_VERIFIED | Artifact/Element/Chunk UTF-16 范围、页码、预览和关键词投影完成真实对账；BBox、Graph-only 和原件版面高亮除外 |
| 多文件抽取测试与正式摄取 | IMPLEMENTED | ACCEPTANCE_VERIFIED | 浏览器真实覆盖 TEST_ONLY 双文件、取消、INGEST 首次发布、重复短路和 externalId 冲突；失败/取消保留 OSS，逐文件诊断、预览、业务身份与下载可查 |
| TXT/Markdown/HTML/PDF/DOCX 解析和预算（8/11 基线） | IMPLEMENTED | ACCEPTANCE_VERIFIED | Parser 门禁通过，E2E 使用 Markdown/TXT；复杂恶意样本集为 DEFERRED |
| MinIO 原文件、授权下载/预览 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Admin/Reader 原文件权限链路通过；硬崩溃孤儿清理未实现 |
| 文档 ACTIVE/ARCHIVED/DELETED 与修订查看 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Store 回归、活动证据和 Obsidian 删除/恢复链路通过 |
| Neo4j Graph 投影/检索/探索 | IMPLEMENTED | ACCEPTANCE_VERIFIED | 真实 IT 1/1；浏览器等待投影、Graph 来源追溯通过 |
| Wiki 编译、审核、发布、Page Retriever | IMPLEMENTED | ACCEPTANCE_VERIFIED | Admin 浏览器全链路通过；生成式 Wiki GLM 为可选能力 |
| Obsidian manifest 删除/移动对账 | IMPLEMENTED | ACCEPTANCE_VERIFIED | 首次、幂等、删除归档、恢复四步均 `SUCCEEDED` |
| Connector/Evaluation PENDING + 可恢复租约 | IMPLEMENTED | ACCEPTANCE_VERIFIED | 最终单元/Store 门禁通过；服务中断和双实例竞争演练为 DEFERRED |
| 后台调度隔离 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Projection/Connector/Evaluation 使用可配置 3 线程 scheduler；容量门禁为 DEFERRED |
| 检索评测与 baseline/candidate 质量门禁 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Admin E2E 运行链路通过；真实企业语料基线待业务侧建立 |
| 低基数指标、变更审计和管理页 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Admin E2E Audit 通过；生产告警规则为 DEFERRED |
| Agent Java Client / KnowledgeSearchTool | IMPLEMENTED | ACCEPTANCE_VERIFIED | 单元门禁通过；Infinity-Agent 跨仓联调为 DEFERRED |
| OpenAPI | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | 当前 45 paths、52 operations、101 schemas、356 refs，无缺失引用或重复 operationId；Space 创建 Controller 映射已测，真实 HTTP 浏览器响应待整体验收 |
| Query Context / SourceSpan OpenAPI 增量 | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | 请求 Context 与 Citation SourceSpan schema 已同步；最终运行响应验证待执行 |
| Playwright Admin/Reader/Extraction 核心用例 | IMPLEMENTED | FINAL_ACCEPTANCE_PENDING | 2026-08-22 历史抽取基线 4/4；当前套件可发现 8 个场景，新增的 Space 创建固化、`testConfig` 与只读契约仍需在重建环境实跑，不能沿用历史通过状态 |
| Control Plane API/Application 分包 | IMPLEMENTED | TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING | 两层已按业务域拆分；HTTP 路径/JSON 不变，直接 import 内部控制面类型的 Java 代码需更新包名；testCompile 已通过 |

## 3. 2026-08-11 验收证据

### 3.1 最终源码门禁

- `mvnw.cmd -T1 test`：16 个 Reactor 模块，`BUILD SUCCESS`，43.702 秒；0 failure/error，1 个 Windows symlink 条件跳过。
- 关键模块：`control-plane` 98、`knowledge-runtime` 20、`knowledge-domain` 13、`knowledge-spi` 8，均 0 failure/error。
- 配置绑定修复聚焦回归：7/7。
- `mvnw.cmd -T1 -pl control-plane -am package -DskipTests`：15 个 Reactor 模块成功。
- `npm.cmd run lint`、`npm.cmd run build`、`npm.cmd run e2e:list`：通过。
- `git diff --check`：通过。

### 3.2 外部组件

- PostgreSQL：首轮 49 个 IT 中 4 个测试夹具/隔离问题；修正后 3 个失败方法和生命周期用例聚焦通过，迁移及首轮其余 IT 已通过。报告保留该轨迹，不表述为单次 49/49 全绿。
- Acceptance API 启动后 Flyway 到 V15，健康检查和业务 API HTTP 200。
- Elasticsearch 真实 IT 3/3；Milvus 4 pass + 1 GLM-only conditional skip；Neo4j 1/1；GLM Embedding 1/1。
- Keycloak 配置连续两次 exit 0；Admin/Reader 的 tenant、department、audience 和 role 均已验证。
- Console `http://localhost:5173` 和 API `http://localhost:8080` 均返回 HTTP 200。

### 3.3 浏览器和 Obsidian

- Playwright 通过 `E2E_BROWSER_CHANNEL=chrome` 复用本机 Chrome，固定 1 worker。
- Admin 全链路约 1.4 分钟，Reader ACL 场景约 4.9 秒；2/2 通过，总耗时 92.5 秒。
- 首次 E2E 暴露异步投影等待策略不足；修正为写入后等待 KEYWORD/VECTOR 成功，投影等待上限由 30 秒调整为 90 秒，业务条件未放宽。
- Obsidian Vault：首次 `seen=1 changed=1 deleted=0`；第二次 `seen=1 changed=0`；删除后 `seen=0 deleted=1 archived=1`；恢复后 `seen=1 changed=1 active=1`，四次均 `SUCCEEDED`。

完整命令、计数和边界见根目录 [TEST-REPORT.md](../TEST-REPORT.md)。

### 3.4 2026-08-14～16 增量验证边界（历史检查点）

以下内容记录当时的中间检查点，数据抽取部分已经由 3.5 的 2026-08-22 验收取代；
其余 Query/检索增量仍适用。该检查点只允许表述为
`TARGETED_VERIFIED / FINAL_ACCEPTANCE_PENDING`：

- API/Application 业务分包后的 `control-plane testCompile` 已通过；
- 最终共享树已完成摄取/配置相关 Maven 聚焦回归和前端 lint/build；尚未重跑一次全仓 Maven
  门禁与浏览器 E2E；
- 当时尚未按最终 schema 重建 PostgreSQL；抽取范围后来已由 3.5 的 V1～V18、MinIO 和
  ES KEYWORD 对账闭环，Milvus/Neo4j 的本轮增量仍不在抽取验收范围；
- 没有用真实 GLM 对语义切分、Query Planner、专用 Rerank 做配额、延迟、失败和结果质量验收；
- 没有用同一企业 Dataset 比较 Baseline 与 Candidate 的 Recall/MRR/nDCG、引用准确率、延迟和成本。

因此，3.1～3.3 的命令、计数和浏览器结果只证明 2026-08-11 历史基线，不能当成本轮增量证据。

### 3.5 2026-08-22 数据抽取阶段受控验收

- PostgreSQL：目标开发库按授权精确重建 `public` schema，Flyway V1～V18 共 18 条记录
  全部成功；`PostgresExtractionRunStoreIT` 7/7，覆盖 Space 单活动任务、取消、发布窗口、
  `SUCCEEDED/SKIPPED_DUPLICATE` 和正式输出 ID。
- MinIO：真实随机对象写入、中文文件名、长度/SHA/属性、下载和精确删除 1/1；API 验收中的
  失败、取消、重复和冲突原件均按产品语义保留。
- Docling：固定 v1.20.0 digest、DoclingDocument@1.10.0；PDF Golden 成功，DOCX 表格丢失
  稳定 fail-closed 为 `DOCLING_SOURCE_COVERAGE_MISMATCH`，DOCX Adapter 保持不可选择。
- 正式 INGEST：首次两文件产生 2 个 Document、2 个 Revision、3 个 Element、2 个 Chunk；
  第二次相同输入两个 Item 均为 `SKIPPED_DUPLICATE` 并复用原输出；同 externalId 的不同内容
  为 `FAILED/PARSE/EXTERNAL_ID_CONFLICT`，原 Document/Revision/title/authority 均未覆盖。
- 投影：2 个 KEYWORD Projection Job 均 `SUCCEEDED/attempt=1`；Elasticsearch 2 条 Chunk 的
  document/revision/title/authority 与 PostgreSQL 活动修订一致。VECTOR 和外部模型未启用。
- Tokenizer：默认禁用态会在能力目录中显示稳定原因；固定本地 tokenizer.json 启用态完成
  API、页面保存、真实 TEST_ONLY 与配置快照验收，但目标 Embedding Serving 配对仍未配置。
- Console：真实浏览器抽取工作台最终 4/4，覆盖 TEST_ONLY 双文件、精确 Tokenizer、协作
  取消和 INGEST 成功/重复/冲突；逐文件 externalId/title/authority、诊断、预览和下载均读取真实 API。
- 聚焦回归：Runtime Processor 5/5、Control Plane 选择集 27/27；正常 Maven package、
  Console lint、TypeScript 与 production build 通过；
  OpenAPI 45 paths、52 operationIds、100 schemas，引用无缺失；Space 创建新契约的运行态验收待补。

本轮未执行 Wiki、Graph、Milvus 或外部语义模型验收，不能把上述结果外推到这些能力。

### 3.6 2026-08-23 Space 不可变配置复验

- `control-plane -am test`：428 个测试通过，0 失败、0 错误，1 个外部环境条件测试跳过；
- 最终配置/Runtime/Controller 回归 21/21，Pipeline 相关选择集另有 30/30 通过；
- 真实 PostgreSQL IT 15/15，覆盖 Space、ACL、Connector 与配置同事务回滚、并发相同创建
  幂等、不可覆盖，以及 Space/Run/发布配置版本固定为 1；
- Console ESLint、TypeScript、Vite production build 和 8 条 E2E 清单发现通过；完整
  Playwright 未在本轮启动受控外部服务执行；
- OpenAPI 为 45 paths、52 operations、100 schemas、354 refs，无缺失引用或重复
  operationId；`git diff --check` 无空白错误。

### 3.7 2026-08-23 Space 实际处理实现合同聚焦复验

本节新增结果不改写 3.6 的历史计数：

- 合同模型、解析选择、配置解析、任务创建、Worker、抽取执行和漂移失败链共 22 个相关
  测试类、101/101；流程、规范化、Parser、Cleaner、Chunker 五类材料变化均进入总指纹；
- Space 配置和 Run 快照的单元/真实 PostgreSQL 往返 14/14；发布批次边界 3/3，已删除
  无版本/无指纹兼容构造；真实 PostgreSQL 同时覆盖配置缺失和指纹不匹配的零写入拒绝；
- `PostgresKnowledgeStoreIT` 完整回归 21/21、0 跳过；旧测试夹具补齐 V16
  `contextual_text` 后，知识写入、重复/冲突和最终合同指纹栅栏全部通过；
- Console ESLint、TypeScript、生产构建和 8 条 E2E 清单发现通过；页面可查看固化处理
  版本、总指纹和当前部署匹配状态，完整 live Playwright 未执行；
- OpenAPI 为 45 paths、52 operations、101 schemas、356 refs，无缺失引用或重复
  operationId；`git diff --check` 无空白错误。

正式执行不提供历史实现的兼容路由：部署实现与 Space 固化合同不一致时，已有数据仍可查询，
已有投影可继续完成，但旧 Space 的正式摄取会明确失败。采用新实现需创建新 Space 并重新
摄取；完整且当前可执行的 `TEST_ONLY testConfig` 仍可单次运行且不会写回 Space。

## 4. 第一阶段验收结论

2026-08-11 历史基线可表述为：

> **P1 可靠性整改与 P2 知识层核心完成第一阶段本地受控验收，可以进入代码审查和业务数据验收。**

2026-08-23 当前共享树可以表述为：

> **数据抽取历史基线，以及 Space 不可变配置和实际处理合同漂移拦截的代码/数据库聚焦
> 门禁已经完成；当前 8 个浏览器
> 场景整体验收后，可进入业务 Golden Dataset 扩充和后续检索调优阶段。不得宣称生产
> HA、容量或全部知识系统能力已经完成。**

这不等于生产就绪。正式上线仍需根据目标规模补齐：

- 双实例竞争、服务中断恢复、长任务租约运行演练；
- 高并发、容量、长时间稳定性和资源饱和测试；
- 配额/限流、备份恢复演练、跨区域 HA；
- 完整 SLO/告警、跨服务 OTel；
- 真实企业知识集的检索质量基线；
- Infinity-Agent 外部仓库端到端联调。

## 5. NOT_SUPPORTED

- Excel、PPT、图片 OCR、网页爬取；
- Query Decomposition、多跳迭代、领域词典、父子/相邻 Chunk 扩展；
- 经目标 Embedding Serving 真实配对验收的 Tokenizer、完整层级和表格/代码/日志专用 Chunk 策略；本地 HuggingFace Tokenizer 适配器已交付；
- Query 变体/Planner 模型和 Reranker 原因码的完整 Trace、成本指标与评测结果持久化；
- 独立 Planner/Retriever/Reranker bulkhead，以及 resolved entities/business context 专用上下文字段；
- Java `knowledge-agent-client` 的 Query Context 请求模型和 Console 输入表单；当前 HTTP/OpenAPI/
  Console API Client 已支持该字段；
- 用户指定历史修订检索；当前 Agent/RAG 检索只接受活动修订；
- Docling OCR/Profile 完整参数化、页眉页脚/水印的高质量自动识别、PDF bbox、跨页 Provenance 和原件版面高亮；`parser-docling` 的 PDF/DOCX 结构映射已交付；
- 正式处理配置变化采用“创建新 Space、重新摄取、调用方切换 `spaceId`”，不再规划原 Space
  的配置提升、影子重处理和索引代际状态机；多文件 `TEST_ONLY/INGEST`、Element/Chunk/边界
  预览、不可变配置快照、测试运行对比、真实 Gate 与正式发布已完成；
- SourceSpan 的历史数据精确回填；V16 历史 Chunk 只能安全回填 `contextualText=content`，范围为空；
- Graph-only Evidence 的 Element 范围；Neo4j 当前只保存 Chunk 级 Provenance；
- Owner、有效期、保密等级、组织审核的完整治理；
- Wiki Claim/Link/Diff/回滚、来源影响分析、自动重编译；
- Graph 标注集、Entity/Relation/Provenance 质量指标；
- Obsidian 事件增量、多用户可信副本/稳定文件身份；当前为手动完整快照与 Manifest 对账；
- 第二个真实 Connector、定时源发现、通用 Connector 市场；
- 最终答案生成和忠实度/引用完整性评测。
