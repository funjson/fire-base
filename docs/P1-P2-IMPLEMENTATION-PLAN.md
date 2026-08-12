# P1/P2 企业知识层实施清单

> 分支：`codex/p1-p2-knowledge-layer`
> 本文件记录计划与实际收敛，不用“计划存在”代替实现或验证。

状态：`DONE`、`PARTIAL`、`DEFERRED`、`FINAL_ACCEPTANCE_PENDING`。

## 1. 资源约束

- Maven 使用 `-T1` 和定向模块测试，不反复 `clean verify`；
- 开发阶段不批量调用 GLM、不做全库重建、不做高并发压测；
- 外部组件只在一次受控最终验收中集中启动；
- Graph/Wiki 使用小型确定性 Fixture；
- Playwright 固定单 Worker，不自动启动服务或下载浏览器。

## 2. 实施状态

### A. 生产正确性基座

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| Projection heartbeat、fencing、dirty/requeue | DONE | V8 + Worker/Queue 定向测试 |
| Connector/Evaluation 可恢复租约 | DONE | V10/V14 + Coordinator/Worker 定向测试；snapshot restart 幂等与逐记录续租，真实重启待验收 |
| 文档生命周期与旧证据拒绝 | DONE | ACTIVE/ARCHIVED/DELETED + ActiveRevisionGuard |
| 历史外部投影召回与 Graph bridge 防护 | DONE | ES/Milvus 1x/2x/4x 有界 overfetch；Graph 每跳先过滤活动修订 |
| 请求 Deadline、通道超时与取消 | DONE | 有界执行和稳定 warning/error |
| 后台恢复调度隔离 | DONE | 可配置 3 线程 scheduler；避免长 Graph projection 饿死恢复轮询 |
| 低基数指标和变更审计 | DONE | MeteredTraceSink + V13 Audit/API/UI |
| 物理孤儿对象/旧投影定期清扫 | DEFERRED | 逻辑读取已拒绝；后台 sweeper 未实现 |

### B. 高质量 RAG

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| 真实 Reranker 接入 | DONE | 受预算的 Embedding Cosine Reranker，可安全 fallback |
| 跨 PostgreSQL/ES/Milvus Filter 一致性 | DONE | sourceType/language + ACL/活动修订守卫 |
| 评测基线、对比和阈值门禁 | DONE | hitRate/Recall/MRR/nDCG + regression gate |
| Query Rewrite / Multi-query / 领域词典 | DEFERRED | 当前 Analyzer 为确定性规范化/通道路由 |
| 父子 Chunk / 相邻上下文 / 充分性重试 | DEFERRED | 当前返回独立有来源 Chunk |
| Embedding generation 蓝绿自动迁移 | PARTIAL | generation 字段存在；自动双写/切换未实现 |

### C. 知识资产与外部来源

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| MinIO 原文件/附件 | DONE | 不暴露对象 Key，授权下载/安全预览 |
| TXT/Markdown/HTML/PDF/DOCX | DONE | 有资源和 ZIP 安全预算 |
| Excel/PPT/OCR | DEFERRED | 未实现 |
| 修订、归档、删除、恢复 | DONE | 乐观版本；管理历史 Chunk |
| Owner/有效期/保密等级/组织审核 | DEFERRED | metadata 不能替代正式治理模型 |
| Obsidian 删除/移动对账 | DONE | 成功完整 Snapshot Manifest 才归档缺失文档 |
| 第二个 Connector / 通用市场 | DEFERRED | Provider SPI 已有，尚无第二个真实实现 |

### D. Graph 知识层

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| Entity/Relation/Event/Provenance 模型 | DONE | 领域不依赖 Neo4j |
| Neo4j tenant/space/doc/revision 隔离 | DONE | 固定 Cypher、来源边界和索引/约束 |
| GLM 抽取、幂等投影和活动修订清理 | DONE | 真实 Neo4j/GLM E2E 待验收 |
| Graph Retriever 与融合 | DONE | 关系词路由、最大 hops/results、Evidence |
| Graph Explorer API/UI | DONE | Admin tenant/ACL 范围 |
| Entity/Relation/Provenance 质量指标 | DEFERRED | 尚无人工标注数据集和指标 Runner |

### E. Wiki 知识层

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| Page/Revision/Source Reference | DONE | 不可变页面修订和来源覆盖 |
| 抽取式/可选 GLM 编译 | DONE | GLM 必须显式启用 |
| Draft/Review/Publish/Archive | DONE | expectedVersion 乐观并发 |
| Published Page Retrieval | DONE | 返回活动原始 Chunk，不把生成文本冒充事实 |
| Claim/Link/Diff/回滚 | DEFERRED | 未实现 |
| 来源影响分析/自动增量重编译 | DEFERRED | 未实现 |

### F. Agent 与管理产品

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| OpenAPI | DONE | `docs/openapi.yaml`；运行响应验证待验收 |
| Java KnowledgeSearchTool | DONE | 独立模块 4 个测试通过 |
| Infinity-Agent 真正联调 | FINAL_ACCEPTANCE_PENDING | 本分支未执行外部仓库 E2E |
| 文档/Graph/Wiki/Evaluation/Audit 管理页 | DONE | 最新完整前端门禁待重跑 |
| Playwright Admin/Reader 核心用例 | DONE | 单 Worker；尚未实际运行 |
| 配额、备份恢复、HA、SLO/告警 | DEFERRED | 独立生产化阶段 |

## 3. 最终验收

最终验收必须执行并记录：

1. 单线程 Maven 完整门禁与前端 lint/build；
2. PostgreSQL V1 -> V15、ES、Milvus、MinIO、Neo4j、GLM 真实契约；
3. Admin/Reader/Service Principal API 与 Playwright；
4. 富文档、Obsidian reconcile、Graph、Wiki、Agent Tool 全链路；
5. 重启恢复、删除/撤权/旧修订、队列饱和和降级；
6. Retrieval baseline/candidate 质量门禁和审计/指标。

容量压测、备份恢复和生产 SLO 不属于本次低 CPU 开发验收，不能因此宣称生产
就绪。当前执行证据见 [P1/P2 状态](P1-P2-STATUS.md)。
