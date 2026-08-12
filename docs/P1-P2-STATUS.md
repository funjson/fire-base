# P1/P2 企业知识层状态

> 状态日期：2026-08-11
>
> 分支：`codex/p1-p2-knowledge-layer`
>
> 当前结论：P1 可靠性整改与 P2 知识层核心已通过第一阶段本地受控验收；生产化、HA 和容量门禁仍待完成。

## 1. 状态定义

- `IMPLEMENTED`：源码、契约和管理入口已存在。
- `ACCEPTANCE_VERIFIED`：本轮在最终工作树或真实组件上完成受控验收。
- `DEFERRED`：不阻塞第一阶段代码验收，但上线前需按部署目标补充。
- `NOT_SUPPORTED`：当前没有可用实现，不得作为交付能力宣传。

`ACCEPTANCE_VERIFIED` 仅代表本地 Docker、低 CPU、单 Worker 场景，不代表生产 HA、容量或长期稳定性。

## 2. 能力矩阵

| 能力 | 实现 | 当前验证 | 说明 |
|---|---|---|---|
| OIDC、多租户、用户/角色/部门/租户 ACL | IMPLEMENTED | ACCEPTANCE_VERIFIED | Keycloak 幂等配置、Claim 和 Admin/Reader 浏览器矩阵通过 |
| 不可变修订、A -> B -> A、活动修订守卫 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Flyway V15、Store IT 与运行链路通过；物理 GC 延后 |
| Projection heartbeat/fencing/dirty-requeue | IMPLEMENTED | ACCEPTANCE_VERIFIED | 单元/Store 回归和 E2E 实际投影通过；双实例竞争为 DEFERRED |
| 历史投影有界 overfetch 与 Graph 逐跳守卫 | IMPLEMENTED | ACCEPTANCE_VERIFIED | ES、Milvus、Neo4j 真实契约通过 |
| Deadline、通道超时、取消、有界队列 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Runtime 门禁通过；高并发容量测试为 DEFERRED |
| ES + Milvus + RRF + Embedding Rerank | IMPLEMENTED | ACCEPTANCE_VERIFIED | ES 3/3、Milvus 4 pass + 1 conditional skip、GLM 1/1，浏览器 Evidence 链路通过 |
| TXT/Markdown/HTML/PDF/DOCX 解析和预算 | IMPLEMENTED | ACCEPTANCE_VERIFIED | Parser 门禁通过，E2E 使用 Markdown/TXT；复杂恶意样本集为 DEFERRED |
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
| OpenAPI | IMPLEMENTED | ACCEPTANCE_VERIFIED | 37 paths、43 operations，引用可解析；API 运行健康 |
| Playwright Admin/Reader 核心用例 | IMPLEMENTED | ACCEPTANCE_VERIFIED | 本机 Chrome、1 worker、2/2，总耗时 92.5 秒 |

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

## 4. 第一阶段验收结论

当前可表述为：

> **P1 可靠性整改与 P2 知识层核心完成第一阶段本地受控验收，可以进入代码审查和业务数据验收。**

这不等于生产就绪。正式上线仍需根据目标规模补齐：

- 双实例竞争、服务中断恢复、长任务租约运行演练；
- 高并发、容量、长时间稳定性和资源饱和测试；
- 配额/限流、备份恢复演练、跨区域 HA；
- 完整 SLO/告警、跨服务 OTel；
- 真实企业知识集的检索质量基线；
- Infinity-Agent 外部仓库端到端联调。

## 5. NOT_SUPPORTED

- Excel、PPT、图片 OCR、网页爬取；
- Query Rewrite、Multi-query、父子/相邻 Chunk 扩展；
- Owner、有效期、保密等级、组织审核的完整治理；
- Wiki Claim/Link/Diff/回滚、来源影响分析、自动重编译；
- Graph 标注集、Entity/Relation/Provenance 质量指标；
- 第二个真实 Connector、定时源发现、通用 Connector 市场；
- 最终答案生成和忠实度/引用完整性评测。
