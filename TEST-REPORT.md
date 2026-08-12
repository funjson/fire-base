# Infinity Knowledge Runtime P1/P2 测试报告

> 更新日期：2026-08-11
>
> 分支：`codex/p1-p2-knowledge-layer`
>
> 结论：P1 可靠性整改与 P2 知识层在本地受控验收环境通过；该结论不代表生产 HA、容量或灾备验收完成。

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

## 7. 未支持与延期边界

### NOT_SUPPORTED

- Excel、PPT、图片 OCR、通用网页爬取；
- Query Rewrite、Multi-query、父子/相邻 Chunk 自动扩展；
- Owner、有效期、保密等级和组织审核的完整治理；
- Wiki Claim/Link/Diff/回滚、影响分析和自动增量重编译；
- Graph 标注集及 Entity/Relation/Provenance 质量指标；
- 第二个真实 Connector、通用 Connector 市场；
- 最终答案生成及忠实度/引用完整性评测。

### DEFERRED

- 双实例竞争、服务进程中断后的运行恢复演练；
- 高并发、容量、长时间稳定性和资源饱和测试；
- 配额/限流、备份恢复演练、跨区域 HA；
- 完整 SLO/告警和跨服务 OTel；
- 真实企业知识集的检索质量基线与发布门槛；
- Infinity-Agent 外部仓库端到端联调。

## 8. 结论

> **`codex/p1-p2-knowledge-layer` 已达到 P1/P2 第一阶段本地受控验收条件：最终全量单元门禁、真实核心 Adapter、OIDC、多租户 ACL、API/UI、Admin/Reader 浏览器链路和 Obsidian 对账均已验证。**

该结论可以用于进入代码审查和业务数据验收，但不能宣称生产就绪或生产 HA。正式上线前仍需完成第 7 节 DEFERRED 项中与目标部署规模相关的门禁。
