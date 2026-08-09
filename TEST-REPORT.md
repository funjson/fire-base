# Infinity Knowledge Runtime Phase 1 测试报告

> 日期：2026-08-09  
> 测试依据：[TEST-CASES.md](TEST-CASES.md)  
> 当前用途：记录整改前基线、修复状态、分片验证证据和最终验收缺口

## 1. 当前结论

整改前黑盒与代码审计发现的 F-001～F-010 均已有对应代码修复；本轮静态复核
新增的 F-011～F-018 也已完成代码整改。文档身份、
A→B→A、活动修订投影保护、文档管理查询、ACL/Reader、稳定 DTO/requestId、
Obsidian 实路径、Strict Hybrid、Connector Provider/Run 和 Evaluation Store
已进入共享源码。

本轮较早的子任务曾分别通过 PostgreSQL、Elasticsearch、Milvus 分片验证，
前端 Lint 和 TypeScript 编译也曾通过，但中央活动修订校验、V7 修订指纹、
文档投影详情与评测页面等最终改动合并后，尚未在本报告中执行一次新的全量
Maven、前端 Build 和浏览器/API 冒烟。因此
当前结论是：

> **代码整改已完成，分片验证通过；Phase 1 等待合并后最终验收，不宣称生产就绪。**

## 2. 整改前基线

整改前运行中系统已确认：

- Admin 可以通过 Keycloak 登录；
- 可以创建空间、写入 Markdown、生成 Element/Chunk；
- PostgreSQL 检索可以返回 Evidence/Citation/Trace；
- 管理控制台、Connector 和 Evaluation 基础页面存在；
- Dataset/Case/Run 可以产出 Recall@K、MRR、nDCG。

同时发现：

- 相同 `externalId` 跨空间污染文档身份；
- A→B→A 不会恢复历史修订；
- 文档列表合法过滤组合返回 500；
- Reader/ACL、requestId、URI 和混合运行配置不闭环；
- ES/Milvus 存在旧修订投影风险；
- Control Plane 直接 SQL 和具体 Connector 构造偏离模块边界。

原黑盒环境状态只代表整改前快照，不应作为整改后运行结果复用。

## 3. 缺陷修复矩阵

状态说明：

- `TARGETED_VERIFIED`：对应自动化分片已实际通过；
- `FIXED_CODE`：实现已合入，仍需合并后全量或浏览器验证；
- `PENDING_RUNTIME`：需要运行中系统复测。

| 缺陷 | 原问题 | 当前状态 | 证据/待验证 |
|---|---|---|---|
| F-001 | 相同外部 ID 跨空间污染 | `TARGETED_VERIFIED` | 空间级 Connector、完整 Source Key、V5 迁移；PG IT 通过 |
| F-002 | A→B→A 不恢复、并发修订号风险 | `TARGETED_VERIFIED` | 历史修订激活、行锁内分配；PG IT 通过 |
| F-003 | 文档列表合法过滤组合 500 | `TARGETED_VERIFIED` | 动态 WHERE、分页读模型；管理 Store 5 个 PG IT 通过 |
| F-004 | HTTP/Bundle requestId 不一致 | `TARGETED_VERIFIED` | 入口单一 UUID；Filter/DTO 单元测试分片通过 |
| F-005 | 危险来源 URI 可写入/点击 | `FIXED_CODE` | 配置化后端协议白名单和前端独立安全渲染；最新后端单元测试待全量复跑 |
| F-006 | Reader 和 ACL 无法验收 | `FIXED_CODE` / `PENDING_RUNTIME` | Principal 入驻、ACL API、可访问空间、Reader 导航；PG 治理 2 IT 通过，浏览器待验 |
| F-007 | localhost/127/CORS/OIDC 不一致 | `FIXED_CODE` / `PENDING_RUNTIME` | 5173 dev/preview、双 Origin、幂等 Keycloak 配置；登录矩阵待验 |
| F-008 | 实际运行不是 Hybrid | `FIXED_CODE` / `PENDING_RUNTIME` | `acceptance` Strict Hybrid + fail-fast；需真实 GLM/ES/Milvus 启动复测 |
| F-009 | Milvus 模块真实 IT 类路径失败 | `TARGETED_VERIFIED` | 直接 SLF4J 依赖；真实 IT 2 通过、同组 1 条条件跳过 |
| F-010 | 未知前端路由白屏 | `FIXED_CODE` | 404 和 Error Boundary；Lint/TypeScript 通过，Vite 打包受沙箱限制 |
| F-011 | 总览使用不存在的 `LEASED` 投影状态 | `FIXED_CODE` | 改为统计 PENDING/RETRY/RUNNING；PostgreSQL 回归用例已补、待复跑 |
| F-012 | 同正文元数据变化或归档恢复不重建投影 | `FIXED_CODE` | Writer 识别投影字段变化和归档状态，复用修订并重排投影；PG 用例已补、待复跑 |
| F-013 | 同正文但语言/处理契约变化复用旧修订 | `FIXED_CODE` | V7 完整修订指纹、确定性 Revision ID 与 PostgreSQL 回归用例已补；待复跑 |
| F-014 | 关闭 ES/Milvus 时空间重建返回 500 | `FIXED_CODE` | 无外部通道返回 `jobs=0`/空类型，单元用例已补；待复跑 |
| F-015 | 元数据更新不递增版本、历史回放使更新时间倒退 | `FIXED_CODE` | 聚合 version 单次递增、`GREATEST` 保证时间单调；PG 用例已补、待复跑 |
| F-016 | 投影重试会命中历史修订的同类型 DEAD Job | `FIXED_CODE` | 状态查询和 retry 均收紧到当前活动修订；PG 回归用例已补、待复跑 |
| F-017 | 文档投影重试状态会串到其他文档抽屉 | `FIXED_CODE` | pending/error 按 documentId 归属，重试期间禁用重复提交；前端待复跑 |
| F-018 | 异步评测 Run 会覆盖其他数据集的选中详情 | `FIXED_CODE` | Run 选择绑定 datasetId，成功回调按请求数据集刷新；前端待复跑 |

## 4. 架构整改验证

| 范围 | 结果 |
|---|---|
| 管理 SQL 下沉 | `KnowledgeAdministrationStore` + PostgreSQL Adapter |
| 治理 SQL 下沉 | `KnowledgeGovernanceStore` + PostgreSQL Adapter |
| Evaluation SQL 下沉 | `EvaluationStore` + PostgreSQL Adapter |
| Connector SQL 下沉 | `ConnectorStateStore` + PostgreSQL Adapter |
| Connector 扩展 | `SourceConnectorProvider`，应用层不直接构造 Obsidian |
| 稳定查询契约 | `KnowledgeQueryResponse` 将 Value Object 映射为标量 |
| 活动修订事实 | `ActiveRevisionGuard` 用于 Worker 和检索结果 |
| 历史回填入口 | 按空间 Projection Rebuild API/控制台操作 |

代码扫描结果应满足：

```text
control-plane/application
  - 不包含 JdbcTemplate / TransactionTemplate
  - 不包含 SQL
  - 不包含 new ObsidianVaultConnector(...)
```

完整架构状态见 [ARCHITECTURE-AUDIT.md](ARCHITECTURE-AUDIT.md)。

## 5. 已执行自动化证据

这些结果来自本轮整改子任务的实际运行：

| 测试范围 | 结果 |
|---|---|
| PostgreSQL 文档/投影/管理/评测/迁移 | 17 tests，0 failure，0 error |
| PostgreSQL 治理/ACL | 2 tests，0 failure，0 error |
| Elasticsearch 真实 Adapter IT | 2 tests，0 failure，0 error |
| Milvus 真实 Adapter IT | 3 tests：2 通过、1 条件跳过 |
| Runtime/Control Plane 分片单元测试 | 相关切片通过 |
| 前端 | 较早切片的 Lint、TypeScript 编译通过；最终 UI 改动尚未复跑，Vite 生产打包未完成 |
| Keycloak/Acceptance 静态契约 | JSON/Compose/配置分片校验通过 |

限制：

- GLM 真实 API IT 在现存报告中为条件跳过；
- 分片结果发生在共享源码持续合并过程中；
- Connector 轮询、OIDC Audience、AccessScope、中央 Active Revision Guard 和
  最新前端变化需要最后一次全量复跑；
- Writer 同正文投影字段/归档恢复、Overview 状态统计、配置化来源 URI 与 409/503
  契约是在分片验证后新增，已有回归测试但尚未执行；
- V7 完整修订指纹（正文、媒体类型、语言、处理器版本）及其后合并的改动尚未执行
  新一轮全量验证；
- 本报告没有停止、重启或替用户操作当前运行中的服务。

## 6. 最终验收清单

### 6.1 构建门禁

```powershell
.\mvnw.cmd clean verify

Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
```

### 6.2 外部契约

必须分别记录：

- PostgreSQL 全量 IT；
- Elasticsearch 真实 IT；
- Milvus 真实 IT；
- GLM Embedding 真实 API IT；
- `docker compose --profile acceptance config`；
- Strict Hybrid 应用启动探测。

### 6.3 浏览器/API 重点回归

根目录 `TEST-CASES.md` 中所有适用于 Phase 1、且状态不是 `NOT_SUPPORTED` 的
P0/P1 用例都属于最终门禁。以下是本轮整改的重点集合，不是对其余 P0/P1 的
豁免：

- AUTH-001、AUTH-002、AUTH-022～AUTH-025；
- TENANT-004、TENANT-006～TENANT-010；
- CONS-001～CONS-005、CONS-011～CONS-014；
- IDX-001～IDX-009；
- API-015～API-023；
- RET-001～RET-003、RET-011～RET-019；
- UI-004、UI-007、UI-010、UI-016、UI-023～UI-030、UI-032～UI-033；
- CONN-001～CONN-009、CONN-011、CONN-020～CONN-021；
- EVAL-001～EVAL-008。

`CONN-022` 与 `EVAL-020` 的进程重启恢复仍为 `NOT_SUPPORTED`，不属于本阶段
验收项；`CONN-021` 仅验收同一连接器 single-flight，不包含重启恢复。

只有全部适用 P0/P1 均没有回归失败，才可把 Phase 1 标记为 `ACCEPTED`。

## 7. 明确不阻断 Phase 1 的规划边界

以下能力尚未实现，本次应按 `NOT_SUPPORTED` 处理：

- Neo4j/GraphRAG；
- LLM Wiki；
- PDF/DOCX/HTML/Office 解析；
- 知识原文件 MinIO 存储；
- Connector 删除/移动对账和多实例恢复；
- Evaluation 进程恢复；
- 生成答案质量评测；
- 生产 HA、完整 SLO/告警和跨区域容灾。

这些缺口不阻断“Phase 1 RAG 基线”验收，但阻止把当前项目描述为完整生产级
企业知识库。

## 8. 复测结果记录模板

```text
执行时间：
代码版本/Commit：
环境/Profile：
用例 ID：
结果：PASS / FAIL / BLOCKED / NOT_SUPPORTED
requestId / traceId：
HTTP/截图/日志证据：
缺陷 ID：
复测人：
```
