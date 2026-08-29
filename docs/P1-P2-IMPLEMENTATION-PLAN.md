# P1/P2 企业知识层实施清单

> 分支：`codex/p1-p2-knowledge-layer`
> 本文件记录计划与实际收敛，不用“计划存在”代替实现或验证。

状态：`DONE`、`PARTIAL`、`ACCEPTANCE_VERIFIED`、`DEFERRED`、
`FINAL_ACCEPTANCE_PENDING`。

> 2026-08-22 增量说明：数据抽取阶段已经完成 V1～V18、PostgreSQL、MinIO、固定
> Docling、TEST_ONLY/INGEST、关键词投影和浏览器工作台的受控验收。2026-08-11 的全仓
> 与真实组件验收仍是其他能力的历史基线；本次结果不自动覆盖 Query Planner、智谱
> Rerank、Wiki、Graph 或生产化门禁。

## 1. 资源约束

- Maven 使用 `-T1` 和定向模块测试，不反复 `clean verify`；
- 开发阶段不批量调用 GLM、不做全库重建、不做高并发压测；
- 外部组件只在一次受控最终验收中集中启动；
- Graph/Wiki 使用小型确定性 Fixture；
- Playwright 固定单 Worker，不自动启动服务或下载浏览器。

本项目仍处于预发布阶段。空间处理配置草案已合并为最终 V17，V18 保存统一抽取任务、
SourceAsset、配置快照、诊断、预览与正式发布结果；旧数据库不做原地兼容迁移。本轮已按
授权保留 MinIO 原件并从 V1～V18 重建 PostgreSQL schema。其他环境升级前仍应备份并按
部署范围清理、重建外部派生投影。

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
| 真实 Reranker 接入 | DONE | 支持 Embedding Cosine 与智谱专用 `/rerank`；模型分数成为 Evidence 最终相关性，模型失败/超时回退 RRF |
| 跨 PostgreSQL/ES/Milvus Filter 一致性 | DONE | sourceType/language + ACL/活动修订守卫 |
| 评测基线、对比和阈值门禁 | DONE | hitRate/Recall/MRR/nDCG + regression gate |
| Query Rewrite / Multi-query | PARTIAL | 可选智谱 Planner 生成 standalone/paraphrase；原查询首位参与召回并占保底预算；Decomposition、领域词典和变体级指标未实现 |
| 父子 Chunk / 相邻上下文 / 充分性重试 | DEFERRED | 当前返回独立有来源 Chunk |
| 企业级 Chunk Policy | PARTIAL | 稳定 Provider/Token Counter Registry + 结构 Baseline + 绝对余弦双阈值语义边界增删 + Token 预算/Overlap + 有界模型执行；可选本地 HuggingFace Tokenizer 已接入，真实模型配对验收、内容类型策略和父子层级未完成 |
| Stateless Query Planner | PARTIAL | Runtime 不保存会话；结构化 JSON、最多 4 个模型变体、4 秒阶段超时和稳定回退已实现；Decomposition、独立 bulkhead 和完整计划审计未实现 |
| 显式 Query Context Envelope | PARTIAL | HTTP/OpenAPI/Console API Client 支持 4000 字摘要、最多 8 条最近轮次、领域总量 20000 字；Console 表单、Java Agent Client、resolved entities/business context 尚未接入 |
| 可选历史修订检索 | DEFERRED | 默认只查当前活动修订；显式传 `documentId + revisionId` 时才进入历史模式，并单独执行租户、文档与历史读取授权 |
| SourceSpan 与 contextualText | PARTIAL | V16 保存 Element 内 UTF-16 范围和可选页码；原文与向量文本分离并贯穿 PG/ES/Milvus/Page、API/Java Client/Console 类型；Graph-only、bbox、版面高亮和历史范围回填未实现 |
| Embedding generation 蓝绿自动迁移 | PARTIAL | generation 字段存在；自动双写/切换未实现 |

### C. 知识资产与外部来源

| 项目 | 状态 | 结果/边界 |
|---|---|---|
| MinIO 原文件/附件 | DONE | 不暴露对象 Key，授权下载/安全预览 |
| TXT/Markdown/HTML/PDF/DOCX | DONE | 有资源和 ZIP 安全预算 |
| 统一摄取主线 | DONE | Markdown/Connector 复用同步 Pipeline；正式文件上传只走异步多文件 Run，并与测试广场复用 ExtractionEngine、处理配置快照和运行时发布服务 |
| Parser Registry | DONE | 媒体类型/扩展名/Parser 契约唯一注册；拒绝已声明类型与扩展名冲突；支持新增 `DocumentParser` Bean |
| Chunker Provider/Token Counter Registry | DONE | 最终 V17 契约把稳定 Provider/Tokenizer ID、版本、可用性、精度和 Parser 能力前置条件贯穿运行时/Space/API；内置 `UTF8_BYTE_BUDGET`，并可选注册 `tokenizer-huggingface`；第三方 Chunker 实现未交付 |
| Docling 富文档 Parser | PARTIAL | `parser-docling` 提供 PDF/DOCX 第二实现；固定容器 PDF Golden 已通过，DOCX 表格缺失被覆盖门禁拒绝并保持关闭；OCR、BBox、原生 Artifact 与更完整企业 Corpus 仍待补充 |
| 多文件抽取与摄取 | DONE | Space 页支持 `TEST_ONLY/INGEST`、取消、失败码、不可变配置快照、Element/Chunk/SourceSpan 预览、真实 Dataset Gate、重复短路、Document/Revision 输出与 OSS 原件下载；正式处理配置变化时创建新 Space，不建设配置提升状态机 |
| 企业清洗策略 | PARTIAL | 已支持按 Space 对 Parser 明确标注的页眉、页脚、页码、水印和 Front Matter 执行 `KEEP/REMOVE/METADATA_ONLY`；内置富文档 Parser 尚缺高质量版面角色识别 |
| Excel/PPT/OCR | DEFERRED | 未实现 |
| 修订、归档、删除、恢复 | DONE | 乐观版本；管理历史 Chunk |
| Owner/有效期/保密等级/组织审核 | DEFERRED | metadata 不能替代正式治理模型 |
| Obsidian 删除/移动对账 | DONE | 成功完整 Snapshot Manifest 才归档缺失文档 |
| Obsidian 增量事件 + 周期全量校准 | DEFERRED | 目标为首次全量、事件增量、低频 reconciliation；多人协作需单一可信副本、稳定文件身份和变更防抖 |
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
| OpenAPI | DONE | `docs/openapi.yaml` 当前 45 paths/52 operations；新增创建前处理能力目录，Space 创建携带不可变配置，TEST_ONLY 使用 `testConfig` |
| Java KnowledgeSearchTool | DONE | 独立模块 4 个测试通过 |
| Infinity-Agent 真正联调 | FINAL_ACCEPTANCE_PENDING | 本分支未执行外部仓库 E2E |
| 文档/Graph/Wiki/Evaluation/Audit 管理页 | DONE | 最新完整前端门禁待重跑 |
| Playwright Admin/Reader/Extraction 核心用例 | DONE | 当前可发现 8 个串行场景；历史 Admin/Reader 2/2、抽取基线 4/4，新增的 Space 创建固化与 `testConfig` 契约待重建环境实跑 |
| 配额、备份恢复、HA、SLO/告警 | DEFERRED | 独立生产化阶段 |
| Control Plane API/Application 分包 | DONE | 两层均按 ingestion/retrieval/connector/evaluation/wiki/graph/governance/projection/audit/common 分包；HTTP/JSON 不变，内部 Java 包名调整；最终全仓验证待执行 |

## 2.1 已记录的下一阶段设计约束

1. 用户检索不传修订条件时，语义保持为“仅检索当前活动修订”。历史检索必须显式绑定
   `documentId + revisionId`，响应 Citation 和 Trace 标记历史模式；不得通过绕开
   `ActiveRevisionGuard` 的方式隐式放开旧知识。
2. 富文档解析优先评估 Docling，但不把内部领域模型直接替换为第三方对象。通过 Adapter
   映射到 `KnowledgeElement`、层级关系和 `SourceSpan`，这样解析供应商可替换，Chunk、引用、
   权限和修订语义仍由本系统控制。
3. 清洗是企业策略，不是解析器的硬编码副作用。解析器负责识别版面角色和来源坐标；本系统
   负责按租户/空间规则保留、删除或降权，并让规则版本进入修订处理指纹。
4. V16 已把 `ChunkSourceSpan` 从 Chunk 贯穿 PostgreSQL/ES/Milvus、Evidence、Citation、
   HTTP API 和 Java Client；当前范围相对 Element 正文使用 UTF-16 `[start,end)`，并携带
   Parser 能可靠提供的可选页码。Docling Provenance、bbox、跨页版面范围和前端原件高亮仍是目标。
5. Obsidian 的目标同步模型为“首次全量 + 事件增量 + 低频全量校准”。全量校准仍保留，负责
   修复漏事件并进行删除/移动对账；事件链不能替代最终 reconciliation。

## 2.2 Enterprise Chunking：已实现与缺口

默认 `STRUCTURAL` Provider 与 `SEMANTIC_REFINEMENT` 共用同一套结构规划、Token 预算、
Chunk 物化、稳定 ID 和 SourceSpan 链路。语义 Provider 只作用于结构允许调整的相邻片段位置，避免两套
近似实现产生漂移；当前不存在绕过结构硬边界、大小约束和引用约束的纯语义模式。

已实现：

1. 最终 V17 以 `provider_id`、`tokenizer_id`、`minimum_tokens`、`target_tokens`、
   `maximum_tokens`、`overlap_tokens` 和 `provider_config` 保存 Space 配置。Provider 目录暴露
   `id/version/available/unavailableReason/requiredParserCapabilities/defaultProviderConfig`，
   Token Counter 目录暴露 `id/version/description/exactModelTokens/modelProfileId/available/unavailableReason`；
   未启用的 HuggingFace Adapter 仍可见但不可选择，两类 ID 都由动态 Registry 提供。
2. 后端按有效 Parser 的 `outputCapabilities` 校验所选 Chunker 的前置条件，并拒绝 Provider
   未安装、不可用或能力组合不兼容的配置，不能依赖控制台前端兜底。
3. 标题、表格、代码等形成不可跨越的结构硬边界；普通段落/列表相邻片段作为可调整位置。
   `minimumTokens/targetTokens/maximumTokens/overlapTokens` 由所选 Token Counter 统一计量。
4. `SEMANTIC_REFINEMENT` 使用绝对余弦双阈值：相似度不高于拆分阈值时在可调整位置新增断点，
   不低于合并阈值时删除既有 Baseline 软断点，中间区间保留结构基线。`contextSlices` 可让边界两侧各补充
   0～2 个相邻 ElementSlice；默认 split=0.60、merge=0.85、contextSlices=1。
5. 语义模型阶段默认最多 128 个 Embedding 输入、127 个候选边界、262144 个向量标量、单输入
   16000 字符和 12 秒总时限；单线程执行、1 个等待任务，满载立即拒绝。
6. Provider 失败、超时、结果数量/索引/维度/非有限值或零向量错误均产生稳定失败码；同一语义
   `processorVersion` 下不会静默退回 Baseline。切换策略后重试才会形成不同处理契约。
7. `content` 保持可展示和引用的原始正文，`contextualText` 确定性加入章节路径，仅用于向量化；
   SourceSpan 引用仍指向未污染的 Element 正文。V16 约束语义文本非空、范围字段为 JSON 数组；
   写入批次校验 Element 归属和偏移上限，Baseline 将单 Chunk 范围数限制为 128。
8. 统一摄取主线把 normalizer、cleaner、选中 Parser、Token Counter、Provider 配置和 Chunker
   完整契约写入处理指纹；边界增删、大小分布和拒绝合并等诊断以稳定聚合字段写入处理元数据，
   不记录正文、向量或模型响应。Space 创建时由服务端固化 Pipeline、Normalizer Schema、逐格式
   Parser、Cleaner、Chunker/Tokenizer 的完整实际实现合同和总指纹，创建请求不能提交或覆盖
   该合同。新 Run 在保存原件前校验，Worker 在处理任何 Item 前复核，正式发布事务再次比较
   固化指纹；任一同名实现升级、配置文件哈希变化或 Adapter 下线都以稳定错误明确拒绝。
   投影只使用 Space 已存合同生成索引代际，不要求旧 Adapter 仍安装。旧 Space 已有数据可继续
   查询和完成已有投影，但不提供历史实现路由；使用当前实现必须创建新 Space、重新摄取并由
   调用方显式切换 `spaceId`，不在原 Space 内创建影子代际。

仍未完成：

1. 默认 `UTF8_BYTE_BUDGET` 只是 UTF-8 字节预算代理，`exactModelTokens=false`。可选
   `tokenizer-huggingface` 使用本地固定 `tokenizer.json`精确计数，但仍需对目标
   Embedding Serving 执行真实配对 Golden 验收，运维配置的 Profile ID 不能代替验收证据。
2. 语义候选由 ElementSlice 形成；表格、代码、日志、列表尚无重复表头、完整行、代码 AST
   等专用策略。
3. 尚无 Parent/Child Chunk、相邻扩展、按预算回填上下文和 Evidence 充分性二次检索。
4. `parser-docling` 已接入 PDF/DOCX 结构阅读树，但当前规范模型仍未保留 bbox、
   跨页原生 Provenance 和 lossless Artifact；页眉、页脚、水印的高质量自动识别仍未完成。
5. Neo4j Graph 当前只保留 Chunk 级 Provenance；Graph-only Evidence 不能返回 Element 范围。
6. 尚未用同一企业 Dataset 对 Baseline/Semantic 做 Recall、MRR/nDCG、引用准确率、Token 超限率、
   Chunk 数量、延迟和模型成本门禁，因此语义策略不能宣称为默认企业最优策略。
7. Registry 已接入一个外部 Tokenizer 实现，但尚无第三方 Chunker；Provider 专属
   JSON 仍需由对应实现提供权威契约与可动态渲染的 UI Schema。

## 2.3 无状态 Query Understanding / Retrieval Planning：已实现与缺口

Knowledge Runtime 仍然无会话状态，不从 Trace、缓存或服务端 Session 猜测聊天历史。HTTP 请求可选
提交 `conversationSummary`（最多 4000 字）和按时间正序的 `recentTurns`（最多 8 条，每条 4000 字；
Domain 总预算 20000 字），租户、Space 和 ACL 仍完全由认证主体与服务端策略决定。

已实现：

1. 确定性 Analyzer 始终先规范化查询和选择通道；首轮术语增强与反馈 Planner 都不接收 tenant、
   space 或 ACL。
2. 首轮始终包含原查询，可选术语增强由受控 `TerminologyService` 提供；Coverage 之后只有固定 Chain
   已选中的反馈节点可以调用 `ZhipuFeedbackQueryPlanner` 生成一条有界变体。
3. 变体数量和检索轮次由 Space 物化配置与部署硬上限共同约束；反馈规划默认 4 秒阶段总时限、
   3 秒 HTTP 超时、最多 2 次尝试。超时、过载、无效结构或 Provider 失败均产生稳定 warning。
4. 每个通道的总候选预算不会因 Multi-query 成倍增长；有变体时原查询优先保留至少一半预算，
   其余预算再分给生成查询。所有候选仍经过同一 ACL、活动修订守卫、RRF 和最终 Rerank。
5. 智谱专用 Reranker 使用官方 `/api/paas/v4/rerank`，默认最多 24 个候选；返回的
   `relevance_score` 成为已评分 Evidence 的最终 `relevance`。模型预算外稳定补齐项为 0 分并产生
   `RERANKER_PARTIAL`；模型失败或超时保持原 RRF 顺序和分数。

仍未完成：

1. 尚无真正的 `DECOMPOSE`、多跳迭代、充分性反馈和来源选择；当前 Multi-query 只做独立查询和同义改写。
2. Planner 变体的 id/kind/provider/model/promptVersion 未完整进入持久化 Trace；当前 Trace 只记录
   `QUERY_PLANNING` 阶段计数和状态，Reranker 原因码也尚未落入评测结果。
3. Planner、Retriever 和 Reranker 当前复用 Runtime 有界执行器，尚无独立 bulkhead 与分阶段并发配额。
4. Context 尚无 resolved entities、business context 的独立白名单字段，也没有无上下文指代的
   `CONTEXT_REQUIRED` 分类；`knowledge-agent-client` 请求对象也尚未暴露 Context。
5. Evaluation 尚未增加 exact-term preservation、rewrite recall gain、context-required accuracy、
   Planner/Reranker 延迟与成本；未通过业务 Dataset 对比前，模型 Planner 只能保持可选。

## 2.4 官方设计依据

- Microsoft：[基于文档布局的语义切分](https://learn.microsoft.com/en-us/azure/search/search-how-to-semantic-chunking)、
  [Azure AI Search Agentic Retrieval](https://learn.microsoft.com/en-us/azure/search/agentic-retrieval-overview)。
- AWS：[Bedrock Knowledge Bases Chunking](https://docs.aws.amazon.com/bedrock/latest/userguide/kb-chunking.html)、
  [Query Decomposition 与 Reranking](https://docs.aws.amazon.com/bedrock/latest/userguide/kb-test-config.html)。
- Google Cloud：[Parse and chunk documents](https://docs.cloud.google.com/generative-ai-app-builder/docs/parse-chunk-documents)。
- Docling：[DoclingDocument 的结构与 Provenance](https://docling-project.github.io/docling/concepts/docling_document/)、
  [Hybrid/Hierarchical/Line-based Chunking](https://docling-project.github.io/docling/concepts/chunking/)。
- 智谱：[文本重排序 API](https://docs.bigmodel.cn/api-reference/%E6%A8%A1%E5%9E%8B-api/%E6%96%87%E6%9C%AC%E9%87%8D%E6%8E%92%E5%BA%8F)。

这些文档用于确定结构优先、模型 Token 预算、上下文化文本与引用分离、有界 Query Planning、
Rerank 覆盖融合排序等方向；它们不是“当前代码已经等同托管厂商能力”的声明。

## 2.5 当前验收后的富文档增强（已确认）

本轮先按现有抽取验收范围完成代码澄清与受控验收；验收完成后继续实施富文档增强，
不得把以下能力从计划中删除，也不得在尚未交付时写成已验收：

1. 建设经授权、固定 SHA-256 且有人类业务复核的 PDF/DOCX Corpus，覆盖原生文本、
   扫描 OCR、多栏阅读顺序、跨页段落、页眉页脚、脚注、复杂及跨页表格；
2. 为 PDF 增加 bbox、跨页 Provenance 和原件版面高亮；为表格增加行、列、单元格、
   合并关系与重复表头等强类型结构，不再只依赖扁平 TSV；
3. 补齐页眉、页脚、水印、脚注等版面角色识别，使现有 Cleaner 策略真正作用于富文档；
4. 在独立资源预算和安全门禁下增加图片、公式及必要的 OCR/说明抽取，任何不完整结果必须
   明确失败，不能静默丢失或回退；
5. Docling DOCX 在新的固定部署通过表格覆盖 Golden 前继续保持不可选择；验收标准不得为
   适配已知上游缺陷而放宽。

上述工作属于当前抽取验收之后的下一实施阶段；不与本轮验收并行改变基线，以保证验收结果
可复现、问题归因清晰。

## 2.6 验收后的多模型切换与配置（已确认）

语义分块使用的 Embedding 模型和与之配套的 Tokenizer 必须支持多方案选择，但不允许用户
直接填写任意服务地址、模型名或密钥。后续实施遵循以下边界：

1. 建立部署级“模型方案目录”。每个稳定方案 ID 必须绑定 Provider、模型及版本、向量维度、
   最大输入 Token，以及与模型严格配套的 Tokenizer ID、文件哈希和特殊 Token 规则；
2. 服务地址、凭据和可用模型白名单由运维配置；Space 配置和测试请求只选择已安装且可用的
   方案 ID，并通过能力 API/控制台动态展示可选项和不可用原因；
3. 测试运行允许按请求临时覆盖 Space 默认模型方案，但运行创建后必须冻结完整的模型、
   Tokenizer 与分块参数快照和指纹，运行过程中不得因配置刷新而漂移；
4. 第一阶段支持通过外部配置注册多个方案、按 Space 或测试运行选择方案，配置变更后重启
   生效；运行时不停机热加载不作为首期目标；
5. 新增模型厂商通过 Adapter 和注册表接入，核心流程不得增加按厂商分支。模型或 Tokenizer
   的任何变化都必须进入 processorVersion，并使用同一 Dataset 比较质量、延迟和成本。
6. 模型方案属于影响索引结果的 Space 处理配置。测试运行可以临时覆盖；正式切换必须使用
   目标方案创建新 Space，不在已有 Space 上修改模型并触发隐式重索引。

本节只记录验收后的明确需求，不改变当前抽取验收基线，也不表示多模型运行时已经交付。

## 3. 后续全系统最终验收

抽取阶段的本地受控验收已完成；全系统最终验收仍必须执行并记录：

1. 单线程 Maven 完整门禁与前端 lint/build；
2. PostgreSQL 按最终 V1 -> V18 从空 schema 重建；抽取范围的 MinIO、Docling 和 ES
   KEYWORD 已通过，仍需 Milvus、Neo4j、GLM Embedding/Planner/Rerank 的最终工作树真实契约；
3. Admin/Reader/Service Principal API 与 Playwright；
4. 富文档、Obsidian reconcile、Graph、Wiki、Agent Tool 全链路；
5. 重启恢复、删除/撤权/旧修订、队列饱和和降级；
6. Retrieval baseline/candidate 质量门禁和审计/指标。

容量压测、备份恢复和生产 SLO 不属于本次低 CPU 开发验收，不能因此宣称生产
就绪。数据抽取结果已经追加到 `TEST-REPORT.md`，且不覆盖 2026-08-11 的历史记录。
其他增量仍按上述范围验收。当前状态见
[P1/P2 状态](P1-P2-STATUS.md)。
