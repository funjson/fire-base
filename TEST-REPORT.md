# Infinity Knowledge Runtime P1/P2 测试报告

> 历史基线日期：2026-08-11
>
> 数据抽取增量验收日期：2026-08-22
>
> Space 实际处理合同聚焦复验日期：2026-08-23
>
> 分支：`codex/p1-p2-knowledge-layer`
>
> 结论：2026-08-11 的 P1/P2 历史基线继续有效；2026-08-22 的工作树已完成数据抽取
> 阶段受控验收；2026-08-23 当前工作树又完成实际处理合同与漂移拦截的聚焦门禁，完整
> live Playwright 仍待执行。以上结果都不代表生产 HA、容量或灾备验收完成。

## 1. 验收口径

- `PASS`：本轮在最终工作树或对应真实组件上实际执行并通过。
- `PASS_WITH_TRACE`：首轮发现测试夹具或等待策略问题，修正后使用同一业务断言回归通过；保留首轮轨迹。
- `NOT_SUPPORTED`：当前版本没有实现。
- `DEFERRED`：已实现或已有基础，但生产化、容量或跨系统验收不在本轮范围。

本报告中的“验收通过”仅指单机 Docker、本地 Chrome、单 Worker、低 CPU 策略下的第一阶段验收。它不能外推为跨区域 HA、生产容量或长期稳定性结论。

## 2. 验收环境

| 项目 | 实际配置 |
|---|---|
| 日期 | 2026-08-11 |
| 构建策略 | Maven `-T1`；不重复 `clean`，不执行压力测试 |
| 基础设施 | PostgreSQL、Keycloak、MinIO、Milvus、Elasticsearch、Neo4j、etcd |
| API | `http://localhost:8080`，健康检查和业务 API 返回 HTTP 200 |
| Console | `http://localhost:5173`，返回 HTTP 200 |
| 身份 | Keycloak realm `infinity-knowledge`，Admin/Reader 两类用户 |
| 浏览器 | 通过 `E2E_BROWSER_CHANNEL=chrome` 复用本机 Chrome |
| E2E 并发 | Playwright `workers=1` |
| GLM | 使用本机 `ZHIPU_API_KEY`；通过 `127.0.0.1:7890` 代理执行单次真实 Embedding 合约 |

## 3. 静态、构建与单元门禁

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| `git diff --check` | PASS | 无空白错误 |
| `mvnw.cmd -T1 test` | PASS | 最终工作树 16 个 Reactor 模块，`BUILD SUCCESS`，43.702 秒；0 failure/error，1 个 Windows symlink 条件跳过 |
| 关键模块计数 | PASS | `control-plane` 98、`knowledge-runtime` 20、`knowledge-domain` 13、`knowledge-spi` 8，均 0 failure/error |
| `RetrievalPropertiesTest,HybridRuntimeConfigurationTest` | PASS | Spring 配置绑定修复后 7/7 |
| `mvnw.cmd -T1 -pl control-plane -am package -DskipTests` | PASS | 15 个 Reactor 模块打包成功 |
| `npm.cmd run lint` | PASS | 最终前端源码 lint 通过 |
| `npm.cmd run build` | PASS | Vite 生产构建成功 |
| `npm.cmd run e2e:list` | PASS | 发现 2 个串行 Admin/Reader 场景 |
| OpenAPI 静态校验 | PASS | 37 paths、43 operations，引用可解析 |

完整 Maven 门禁在所有本轮改动完成后重新执行；以上不是早期定向测试结果的拼接。

## 4. 真实外部组件契约

| 组件/范围 | 结果 | 实际结果 |
|---|---|---|
| PostgreSQL 迁移与 Store IT | PASS_WITH_TRACE | 首轮 49 个 IT 中 4 个失败，定位为测试夹具/隔离问题：时间类型绑定、V15 后必填 `principal_json`、两个全局队列顺序断言。修正夹具后，3 个失败方法及生命周期用例聚焦回归均通过；迁移测试和首轮其余 IT 已通过。该记录不表述为“一次命令 49/49 全绿” |
| Flyway 运行迁移 | PASS | Acceptance API 启动时真实 PostgreSQL 迁移到 V15，服务健康 |
| Elasticsearch | PASS | 真实 Adapter IT 3/3 |
| Milvus | PASS | 真实 Adapter IT 4 个通过；1 个仅 GLM 条件用例按条件跳过 |
| Neo4j | PASS | 真实 Adapter IT 1/1 |
| GLM Embedding | PASS | 真实 GLM 合约 1/1 |
| Keycloak 配置 | PASS | `keycloak-config` 连续执行 2 次均 exit 0，验证幂等性 |
| Keycloak Claim | PASS | Admin/Reader 均验证 tenant、department、audience、role；Admin 为 admin+reader，Reader 为 reader |

PostgreSQL 的真实运行库由 Flyway 升级至 V15；迁移回归测试覆盖异步历史保留和修订归属约束。任意历史脏数据组合、跨版本生产库演练仍应在正式升级前以生产备份副本执行。

## 5. API、浏览器与业务链路

### 5.1 Playwright

| 场景 | 结果 | 耗时/覆盖 |
|---|---|---|
| Admin 全链路 | PASS | 约 1.4 分钟；空间、文档、异步投影、Evidence/Citation、Evaluation、Wiki 编译/审核/发布/Page Evidence、Graph 投影/来源、Audit |
| Reader ACL | PASS | 约 4.9 秒；允许的 Evidence/原文件访问、管理 API 403、管理导航隐藏 |
| 总计 | PASS | 2/2，1 worker，总耗时 92.5 秒 |

首次 E2E 暴露的是测试同步策略问题：测试在写入后没有等待 KEYWORD/VECTOR 投影成功，且异步投影等待上限 30 秒不足。用例随后改为显式等待两类投影成功，并将投影等待上限调整为 90 秒；业务断言、ACL 断言和成功条件均未放宽。修正后 2/2 通过。

若不下载 Playwright bundled Chromium，可在 PowerShell 中执行：

```powershell
$env:E2E_BROWSER_CHANNEL = 'chrome'
npm.cmd run e2e
```

### 5.2 Obsidian 真实 Vault 对账

专用验收 Vault 的四步结果均为 `SUCCEEDED`：

| 步骤 | seen | changed | deleted | 结果 |
|---|---:|---:|---:|---|
| 首次同步 | 1 | 1 | 0 | 创建并投影文档 |
| 无变化重扫 | 1 | 0 | 0 | 幂等，无重复修订 |
| 删除源文件后 | 0 | 0 | 1 | 对应文档 `archived=1` |
| 恢复源文件后 | 1 | 1 | 0 | 对应文档 `active=1` |

该链路验证了完整成功快照下的 manifest 提升、删除归档和恢复；进程硬崩溃、双实例抢占和超大 Vault 不在本轮运行验收范围。

## 6. P1/P2 验收矩阵

| 能力 | 本轮状态 | 验收证据/边界 |
|---|---|---|
| OIDC、多租户、角色/部门/租户 ACL | PASS | Claim 校验 + Admin/Reader Playwright；未做复杂组织树和跨租户压力测试 |
| 不可变修订、活动修订守卫、生命周期 | PASS | 单元/Store IT、运行迁移及 E2E 活动证据链；批量物理 GC 延后 |
| Projection heartbeat/fencing/dirty-requeue | PASS | 单元/Store 回归 + E2E 实际异步投影；双 Worker 长租约竞争延后 |
| Deadline、通道超时、取消和有界队列 | PASS | Runtime/配置门禁；容量和饱和压力为 DEFERRED |
| Keyword + Vector + RRF + Embedding Rerank | PASS | ES/Milvus/GLM 真实契约与 Admin E2E |
| Graph 投影/检索/来源追溯 | PASS | Neo4j 真实 IT 与 Admin E2E；Graph 人工标注质量集未提供 |
| Wiki 编译/审核/发布/Page Retrieval | PASS | Admin E2E；默认确定性编译，生成式 Wiki GLM 不作为本轮必选门禁 |
| TXT/Markdown/HTML/PDF/DOCX 摄取 | PASS | Parser 单元门禁；E2E 使用 Markdown/TXT，复杂恶意 PDF/DOCX 样本集延后 |
| MinIO 原文件授权访问 | PASS | Admin/Reader 运行链路；对象存储硬崩溃孤儿清理延后 |
| Obsidian manifest reconcile | PASS | 创建、幂等、删除归档、恢复四步真实 Vault 验收 |
| Retrieval Evaluation compare/gate | PASS | Admin E2E 验证运行链路；代表性企业语料的长期质量基线尚需业务侧建立 |
| Evidence/Citation/Trace/Audit | PASS | Admin/Reader E2E；Prometheus 生产告警规则延后 |
| Agent Java Client | PASS | 单元门禁；与外部 Infinity-Agent 仓库的端到端联调为 DEFERRED |
| OpenAPI | PASS | 静态解析通过，并由相同 API/UI 运行链路冒烟；尚未引入自动 breaking-change gate |

## 7. 2026-08-22 数据抽取增量验收

本节是对 2026-08-11 历史报告的追加，不改写当时的命令、计数或结论。

### 7.1 构建与契约

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| 正常 Maven package | PASS | 最新生产 JAR 正常打包，未通过跳过 testCompile 绕过旧测试 |
| Runtime 聚焦 | PASS | `IngestionRunProcessorTest` 5/5 |
| Control Plane 聚焦 | PASS | Pipeline、配置能力、Markdown、正式任务应用服务等选择集 27/27 |
| Parser/Tokenizer | PASS | Ingestion 74、Docling 17；本地 HuggingFace 固定文件及长输入回归通过 |
| Evaluation Golden | PASS | Domain 19、SPI 14、Ingestion 74、Evaluation 9；Markdown/TXT 真实运行 `ExtractionEngine` |
| Console | PASS | ESLint、TypeScript、Vite production build |
| OpenAPI | PASS | 44 paths、52 operationIds、99 schemas，本地引用无缺失 |
| 代码格式 | PASS | `git diff --check` 无空白错误 |

### 7.2 真实外部组件

| 组件/场景 | 结果 | 证据摘要 |
|---|---|---|
| PostgreSQL | PASS | 目标空 schema 连续执行 Flyway V1～V18；`PostgresExtractionRunStoreIT` 7/7 |
| MinIO | PASS | 随机对象写入、中文文件名、长度/SHA、属性、下载和精确删除 1/1 |
| Docling PDF | PASS | 固定 v1.20.0 digest、`DoclingDocument@1.10.0`，真实 Golden 成功 |
| Docling DOCX | PASS_WITH_TRACE | 固定部署丢失源表格，系统稳定返回 `DOCLING_SOURCE_COVERAGE_MISMATCH`；Adapter 保持不可选择 |
| 本地精确 Tokenizer | PASS | 默认禁用态可见且不可选择；固定 SHA-256 启用态完成 API、页面保存、TEST_ONLY 与快照验收 |
| Embedding Serving 配对 | DEFERRED | 本轮没有目标 Serving，明确为 `NOT_CONFIGURED`，不以本地 Tokenizer 结果冒充 |

### 7.3 正式摄取、投影与页面

- 双文件正式 INGEST 首次产生 2 个 Document、2 个 Revision、3 个 Element、2 个 Chunk；
- 第二次相同输入的两个 Item 均为 `SKIPPED_DUPLICATE`，复用已有 Document/Revision；
- 相同 externalId 的不同内容稳定为 `FAILED/PARSE/EXTERNAL_ID_CONFLICT`，未覆盖 title、
  authority、Document 或 Revision；
- 2 个 KEYWORD Projection Job 均 `SUCCEEDED/attempt=1`，Elasticsearch 2 条 Chunk 与
  PostgreSQL 活动修订及逐文件业务身份一致；VECTOR 与外部模型未启用；
- 浏览器抽取工作台 4/4，覆盖双文件 TEST_ONLY、精确 Tokenizer、协作取消，以及正式
  INGEST 的成功、重复和冲突；全部读取真实 API，无 mock；
- TEST_ONLY 对账确认不创建 Document 或 Projection Job；失败、取消和重复原件仍保留在 OSS。

本轮按用户授权只重建了本仓库目标 PostgreSQL 的 `public` schema，旧开发库结构化数据没有
另做备份；MinIO 未清空。验收生成的隔离 Space、Run、SourceAsset、保留原件和关键词投影仍在
受控环境中，未删除其他数据库或对象。

本节只证明数据抽取阶段。Wiki、Graph、Milvus、外部语义模型、生产 HA、容量与灾备不能
从上述结果外推。

### 7.4 2026-08-23 Space 不可变配置契约复验

本节追加记录“创建时固化、测试请求覆盖、正式变更创建新 Space”的最终契约，不改写
2026-08-22 的历史浏览器结果。

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| Control Plane 全量单测 | PASS | `control-plane -am test` 共 428 个测试，0 失败、0 错误；1 个外部环境条件测试跳过 |
| 配置与执行链聚焦 | PASS | 最终 `resolveStored` 边界、Space 创建事务、Controller 与测试覆盖回归 21/21；Parser/Runtime/Pipeline 选择集另有 30/30 通过 |
| 真实 PostgreSQL | PASS | 治理、不可变配置和抽取 Store 15/15；覆盖 Space/ACL/Connector/配置同事务回滚、并发幂等、不可覆盖和固定版本 1 |
| Console 静态与构建 | PASS | ESLint、TypeScript、Vite production build；生产构建转换 2265 个模块 |
| Console E2E 清单 | REVALIDATION_REQUIRED | 当前发现 8 个真实场景，已补充创建配置深比较、重复 204、冲突 409、旧 PUT 405 和 TEST_ONLY 临时覆盖；本轮未启动整套受控外部服务重跑 Playwright |
| OpenAPI | PASS | 45 paths、52 operations、100 schemas、354 refs；无缺失引用或重复 operationId |
| 代码格式 | PASS | `git diff --check` 无空白错误；仅 Windows 行尾提示 |

这次复验确认：请求态配置版本为 0，Space 固化、抽取任务和发布版本固定为 1；正式处理
配置不可修改，完整 `testConfig` 只进入本次 TEST_ONLY 快照。若固定 Adapter 后来下线，
一套完整且当前可执行的测试配置仍可运行，但不会写回 Space 或成为正式摄取的静默回退。

### 7.5 2026-08-23 实际处理合同与运行时漂移拦截

本节是在 7.4“用户配置不可变”之上新增的实现合同门禁，不改写 7.4 的历史计数。Space
不再只固化 Parser/Chunker 等名称和参数，还由服务端固化创建时实际安装的抽取流程、来源
规范化、逐格式 Parser、Cleaner、Chunker/Tokenizer 实现材料及其总 SHA-256。

| 门禁 | 结果 | 证据摘要 |
|---|---|---|
| 合同模型与执行链聚焦 | PASS | 22 个相关测试类共 101/101；流程、规范化、Parser、Cleaner、Chunker 任一实现材料变化都会改变总指纹，损坏或伪造指纹会被拒绝 |
| PostgreSQL 合同与快照往返 | PASS | 配置 Store、Run Store 单元与真实 PostgreSQL 共 14/14；V17 Space 合同和 V18 Run 快照按完整对象往返，不只校验 ID |
| PostgreSQL 知识写入完整回归 | PASS | `PostgresKnowledgeStoreIT` 21/21，0 跳过；旧夹具补齐 V16 `contextual_text` 后，正式写入、重复/冲突及最终合同指纹栅栏均通过 |
| 发布最终栅栏 | PASS | 写入批次边界 3/3，已删除全部无版本/无指纹构造；真实 PostgreSQL 用例同时验证配置缺失、指纹不匹配均在知识表零写入时拒绝 |
| Console 静态与构建 | PASS | 只读配置页展示“固化处理版本”、总指纹和“当前部署匹配/不匹配”；ESLint、TypeScript、Vite production build 通过 |
| Console E2E 清单 | REVALIDATION_REQUIRED | 8 个场景可发现，已增加合同完整性、重复创建合同不变和旧 PUT 405 断言；本轮未运行完整 live Playwright |
| OpenAPI | PASS | 45 paths、52 operations、101 schemas、356 refs；无缺失引用或重复 operationId |
| 代码格式 | PASS | `git diff --check` 无空白错误；仅 Windows 行尾提示 |

运行语义已经固定：正式任务在保存原件前、Worker 处理前和发布事务最终写入前都会校验
合同；漂移时以 `PROCESSING_CONTRACT_MISMATCH` 明确失败，不会在同一版本下静默换实现。
已有数据仍可查询，已存在的投影可按固化合同继续完成；系统不提供历史实现路由，因此旧
Space 不能用漂移后的部署继续正式摄取。正式采用新实现时创建新 Space 并重新摄取；完整且
当前可执行的 `TEST_ONLY testConfig` 仍可做单次实验，但不会改变 Space。

## 8. 未支持与延期边界

### NOT_SUPPORTED

- Excel、PPT、图片 OCR、通用网页爬取；
- Query Decomposition、多跳迭代、父子/相邻 Chunk 自动扩展；
- 第三方 Chunker、表格/代码/日志高级策略和动态 Provider options Schema；
- Owner、有效期、保密等级和组织审核的完整治理；
- Wiki Claim/Link/Diff/回滚、影响分析和自动增量重编译；
- Graph 标注集及 Entity/Relation/Provenance 质量指标；
- 第二个真实 Connector、通用 Connector 市场；
- 最终答案生成及忠实度/引用完整性评测。

### DEFERRED

- 双实例竞争、服务进程中断后的运行恢复演练；
- 目标 Embedding Serving 与本地 Tokenizer 的 Golden 配对；
- 新 Space 重新摄取、调用方切换 `spaceId` 与旧 Space 归档的人工操作验收；
- 极小 OSS 上传/数据库登记窗口的 orphan reconciliation；
- 高并发、容量、长时间稳定性和资源饱和测试；
- 配额/限流、备份恢复演练、跨区域 HA；
- 完整 SLO/告警和跨服务 OTel；
- 真实企业知识集的检索质量基线与发布门槛；
- Infinity-Agent 外部仓库端到端联调。

## 9. 结论

> **`codex/p1-p2-knowledge-layer` 已达到 P1/P2 第一阶段本地受控验收条件：最终全量单元门禁、真实核心 Adapter、OIDC、多租户 ACL、API/UI、Admin/Reader 浏览器链路和 Obsidian 对账均已验证。**

> **数据抽取阶段的 2026-08-22 历史基线已完成本地受控验收；当前工作树又完成 Space
> 创建即固化用户配置、实际处理合同和运行时漂移拦截的代码与真实 PostgreSQL 聚焦门禁。
> 当前 8 个浏览器场景仍需在专用验收环境整体重跑，之后进入业务 Golden Dataset 扩充和
> 检索调优阶段。**

该结论可以用于进入代码审查和业务数据验收，但不能宣称生产就绪或生产 HA。正式上线前
仍需完成第 8 节 DEFERRED 项中与目标部署规模相关的门禁。
