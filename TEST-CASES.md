# Infinity Knowledge Runtime 功能测试用例

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档用途 | 维护前端、API、权限、数据抽取、富文档、检索、Graph、Wiki、评测、连接器及观测回归用例 |
| 当前版本 | 3.0 |
| 基线日期 | 2026-08-22 |
| 默认前端 | `http://localhost:5173` |
| 默认后端 | `http://localhost:8080` |
| 默认 Keycloak | `http://localhost:8180` |
| 默认租户 | `demo` |
| 管理员 | `demo-admin / demo-admin` |
| 只读用户 | `demo-reader / demo-reader` |

### 1.1 优先级

- `P0`：认证、租户隔离、ACL、核心写入/检索等发布阻断能力。
- `P1`：管理、评测、连接器、Trace 等主要产品能力。
- `P2`：易用性、边界条件和增强型质量能力。

### 1.2 执行状态

- `NOT_RUN`：尚未执行。
- `PASS`：结果符合预期。
- `FAIL`：结果不符合预期，必须关联缺陷。
- `BLOCKED`：前置条件或环境能力不具备。
- `NOT_SUPPORTED`：当前版本明确不支持，需与规划保持一致。

## 2. 公共测试数据

| 名称 | 值 |
|---|---|
| 知识空间 ID | `acceptance-engineering` |
| 知识空间名称 | `Acceptance Engineering` |
| 文档 externalId | `acceptance-login-troubleshooting.md` |
| 文档标题 | `验收：用户登录故障排查` |
| 唯一检索词 | `ACCEPTANCE_REDIS_POOL_7319` |
| 文档正文 | `# 用户中心`、`## Redis 超时`、唯一检索词及连接池排查说明 |
| 第二空间 ID | `acceptance-private` |
| 无权限空间 ID | `acceptance-forbidden` |
| 请求关联 ID | `11111111-1111-4111-8111-111111111111` |

## 3. 环境与基础可用性

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| ENV-001 | P0 | 前端可访问 | 打开前端根地址 | 返回管理控制台页面，无白屏或资源 404 |
| ENV-002 | P0 | 后端健康检查 | `GET /actuator/health` | HTTP 200，状态为 `UP`，无需 Token |
| ENV-003 | P0 | OIDC Discovery | 访问 Realm `.well-known/openid-configuration` | HTTP 200，Issuer 与后端配置一致 |
| ENV-004 | P1 | Elasticsearch 健康 | 查询 ES Cluster Health | 集群可用，状态非 red |
| ENV-005 | P1 | Milvus 健康 | 查询 Milvus Health | 服务健康并可建立连接 |
| ENV-006 | P1 | 数据库迁移 | 查看后端启动日志与 Flyway 表 | 预发布空库 V1–V18 连续成功，应用无待执行迁移；V17/V18 的处理配置、SourceAsset 与任务约束存在 |
| ENV-007 | P0 | Keycloak 配置容器可移植性 | 在 Keycloak 健康后执行 `docker compose --profile acceptance run --rm keycloak-config` | 退出码为 0；不依赖官方镜像中未提供的额外 CLI；Client、角色、用户和 Mapper 均完成配置 |
| ENV-008 | P1 | 后台调度隔离 | 阻塞一个 Graph projection，同时观察 Connector/Evaluation recovery | 三类轮询由至少 3 个有界 scheduler 线程执行；长投影不饿死两个恢复任务，不创建无界线程 |

## 4. 认证与授权

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| AUTH-001 | P0 | 管理员浏览器登录 | 使用 `demo-admin` 登录前端 | 跳回前端，显示用户、租户和管理员能力 |
| AUTH-002 | P0 | 只读用户浏览器登录 | 使用 `demo-reader` 登录前端 | 登录成功，不显示或不能执行管理写操作 |
| AUTH-003 | P0 | CLI 获取管理员 Token | 使用 `infinity-knowledge-cli` password grant | HTTP 200，Token 包含 `tenant_id=demo`、管理员角色和 `infinity-knowledge-api` Audience |
| AUTH-004 | P0 | 未认证访问业务 API | 不带 Token 调用 `/api/v1/admin/overview` | HTTP 401 |
| AUTH-005 | P0 | 无管理员角色写入 | reader Token 创建知识空间 | HTTP 403 |
| AUTH-006 | P0 | 无管理员角色读取管理 API | reader Token 查询管理端点 | HTTP 403，或按产品定义返回严格只读范围 |
| AUTH-007 | P0 | 伪造 Token | 使用篡改签名的 JWT 调用 API | HTTP 401，不解析其中租户 |
| AUTH-008 | P1 | Token 过期 | 使用过期 Token 调用 API | HTTP 401，错误不泄露内部堆栈 |
| AUTH-009 | P1 | 前端退出 | 点击退出并返回前端 | Keycloak 会话结束，重新进入要求登录 |
| AUTH-010 | P1 | localhost 回调 | 从 `localhost:5173` 登录 | Token 交换成功 |
| AUTH-011 | P1 | 127.0.0.1 回调 | 从 `127.0.0.1:5173` 登录 | Token 交换成功 |
| AUTH-012 | P1 | 登录错误可诊断 | 制造无效 Client/回调配置 | 页面展示实际 OIDC 错误与来源，不统一误报 Keycloak 未启动 |

## 5. 多租户与 ACL

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| TENANT-001 | P0 | 租户来自 JWT | 请求体传入不同 `tenantId` 或未知字段 | 服务忽略/拒绝伪造值，持久对象仍属于 Token 租户 |
| TENANT-002 | P0 | 跨租户文档读取 | 使用租户 B Token 查询租户 A 文档 | 不返回文档、Chunk、引用或存在性信息 |
| TENANT-003 | P0 | 跨租户 Trace 读取 | 使用租户 B Token 查询租户 A traceId | HTTP 404/403，不泄露 Trace |
| TENANT-004 | P0 | 空间 ACL 召回前过滤 | 用户仅授权空间 A，同时检索 A+B | 仅召回 A，B 不进入模型上下文 |
| TENANT-005 | P0 | Runtime 二次权限校验 | 模拟 Retriever 返回越权候选 | 整个请求拒绝或剔除候选，并产生安全审计信息 |
| TENANT-006 | P1 | 管理列表租户隔离 | 两个租户分别查询空间、文档、评测 | 各自只看到本租户数据 |
| TENANT-007 | P1 | 部门 ACL | 为空间配置部门授权并使用不同部门用户检索 | 仅匹配部门可访问该空间及其文档 |
| TENANT-008 | P1 | 角色 ACL | 为空间配置角色授权并使用不同角色用户检索 | 仅匹配角色可访问该空间及其文档 |
| TENANT-009 | P0 | Reader 可访问空间 | 为 Reader 授权空间 A，调用 `/api/v1/spaces/accessible` | 只返回 A；不依赖 `/api/v1/admin/spaces` |
| TENANT-010 | P0 | ACL 授权与撤销 | 管理员 list/grant/revoke USER/ROLE/DEPARTMENT/TENANT 授权 | 操作租户隔离且幂等；新请求立即得到新的 `AccessScope` |

## 6. 知识空间与文档摄取

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| ING-001 | P0 | 创建知识空间 | 管理员读取能力目录并携带完整处理配置创建公共测试空间 | HTTP 204；Space、版本 1 配置、创建者 ACL 和 API Connector 在同一事务落库，管理列表可见 |
| ING-002 | P0 | 重复空间 ID | 先重复完全相同请求；再禁用原配置引用的可选能力并重复同一请求；最后提交相同 ID 的不同名称、处理配置及非活动 Space | 两次完全相同请求均幂等 204；其余稳定 409，不覆盖配置、不改名、不复活旧 Space，也不产生第二条空间 |
| ING-003 | P1 | 空空间 ID | 创建 `spaceId=""` | HTTP 400，含稳定错误码和 requestId |
| ING-004 | P1 | 非法空间 ID | 使用超长或非法字符 ID | HTTP 400，不进入数据库 |
| ING-005 | P0 | 写入 Markdown | 写入公共测试文档 | 返回 documentId、revisionId、chunkCount |
| ING-006 | P0 | 相同内容幂等写入 | 重复提交相同 externalId 与正文 | 不新增有效修订，不重复投影 |
| ING-007 | P0 | 内容变更创建修订 | 修改同一 externalId 正文后提交 | 创建新修订并切换为 ACTIVE |
| ING-008 | P1 | Markdown 结构解析 | 正文包含标题、列表、代码和段落 | Element/Chunk 保留章节路径与类型 |
| ING-009 | P1 | Chunk 大小边界 | 写入长章节 | Chunk 不超过配置最大值，内容不丢失 |
| ING-010 | P1 | 中文编码 | 写入并读取中文标题、正文和 metadata | 无乱码，检索结果正确 |
| ING-011 | P1 | 空正文 | 提交空或仅空白内容 | HTTP 400 |
| ING-012 | P1 | 不存在空间写文档 | 使用未知 spaceId 写入 | HTTP 404/400，不产生孤儿文档 |
| ING-013 | P1 | authority 边界 | 测试最小、最大及越界值 | 合法边界接受，越界 HTTP 400 |
| ING-014 | P1 | Source URI | 写入合法及非法 URI | 合法保存；非法输入返回验证错误 |
| ING-015 | P1 | Source URI 配置 | 扩展 `KNOWLEDGE_ALLOWED_SOURCE_SCHEMES` 后写入新来源协议 | 仅配置中的绝对 URI 被接受，浏览器链接白名单不随之隐式扩大 |
| ING-016 | P1 | 语言标签规范化 | 分别写入合法、大小写不一致和非法 BCP 47 标签 | 合法标签按 BCP 47 规范化；非法标签 HTTP 400，不写入修订 |
| ING-017 | P1 | 处理契约变更 | 使用新 `spaceId` 和新的 Parser/Chunk 配置创建 Space，再摄取同一正文；另用大小写变体提交 Markdown media type | 新 Space 产生独立修订与 Chunk，旧 Space 配置和数据不变；media type 被规范化，不因大小写产生伪处理身份 |
| ING-018 | P0 | TEST_ONLY 多文件抽取 | 在 Space 数据抽取页一次选择至少两个非 ZIP 文件并运行 | 返回单 Run/N 个 Item；真实执行 Parse/Clean/Chunk；不创建 Document、Revision 或 Projection Job |
| ING-019 | P0 | 正式多文件 INGEST | 为每个文件填写 externalId、title、authority 后提交 | 202 返回；逐文件发布 Document/Revision/Element/Chunk，业务身份与输入顺序一致 |
| ING-020 | P0 | Space 单活动任务 | 同一 Space 的首个 Run 未结束时再次创建 | 第二个请求 409；其他 Space 不受影响 |
| ING-021 | P0 | 取消保留原件 | 取消 QUEUED/RUNNING Run，再下载各 Item 原件 | Run/Item 进入 CANCELLED；MinIO 原件仍可授权下载，不产生正式知识事实 |
| ING-022 | P0 | 重复与冲突 | 先重复相同 externalId+SHA，再以相同 externalId 提交不同内容 | 相同内容 `SKIPPED_DUPLICATE` 并复用输出；不同内容 `EXTERNAL_ID_CONFLICT`，不覆盖既有修订和治理属性 |
| ING-023 | P0 | 不可变配置快照 | 分别用 Space 配置和 `testConfig` 创建 Run 后执行 | Worker 只使用各自创建时快照；实际 Pipeline/Normalizer/Parser/Cleaner/Chunker/Tokenizer 合同、processorVersion 与总指纹可追溯；测试配置不写回 Space |
| ING-024 | P1 | 分层诊断与有界预览 | 完成 TEST_ONLY 后查看 Item 详情 | 展示 Parse/Clean/Chunk 耗时、Clean 去向、16 项 Chunk 诊断、Element/Chunk/SourceSpan 预览；不展示向量或模型原响应 |
| ING-025 | P0 | Golden Dataset 门禁 | 选择受版本控制 Dataset 运行，再与同 Space Baseline 比较 | Observation 由真实 ExtractionEngine 产生；Run 成功与 Gate 通过分离，五项硬门禁任一失败即不可发布 |
| ING-026 | P1 | Parser 能力目录 | 默认部署和启用 Docling 后分别查看 PDF/DOCX Parser | 每种格式恰有一个默认 Parser；Docling PDF 可选且非默认，DOCX 禁用并显示稳定原因 |
| ING-027 | P1 | Tokenizer 两态 | 默认关闭后查看，再启用固定 tokenizer.json/SHA 并运行 TEST_ONLY | 禁用态可见不可选；启用态 exact/profile/版本可查，Run 快照使用选中 ID；不冒充 Serving 配对 |
| ING-028 | P0 | 原件下载安全 | 对成功、失败、取消 Item 下载原件并校验字节/SHA/Header | 字节与 SHA 一致；attachment/no-store/nosniff；不暴露 Bucket、Key、storageId |
| ING-029 | P0 | 创建失败原子回滚 | 使用不可用能力或制造配置写入失败后创建 Space | 请求明确失败；Space、配置、ACL 和 Connector 均无残留 |
| ING-030 | P0 | 配置创建后只读 | 创建 Space 后读取配置并尝试调用旧 PUT | GET 与创建值一致；PUT 路由不存在，执行期缺配置 fail-closed，不补写部署默认值 |
| ING-031 | P1 | 能力目录安全 | 无 Space 时以管理员和非管理员访问全局能力目录 | 管理员可见默认值与可用性；非管理员拒绝；响应不含 Endpoint、凭据或部署秘密 |
| ING-032 | P0 | 同名实现漂移栅栏 | 以实现 v1 创建 Space/排队 Run，再用相同 ID 的 Parser、Cleaner、Chunker 或 Tokenizer v2 创建服务实例 | Space 只读页显示当前部署不匹配；新 Run 在读取/保存原件前 409；已排队 Run 在任何 Item、OSS 读取或模型调用前整单失败，错误码为 `PROCESSING_CONTRACT_MISMATCH` |
| ING-033 | P0 | 发布与投影合同边界 | 在抽取后篡改发布所带合同指纹；另在 Adapter 升级/移除后执行旧修订待处理投影 | 发布事务不写 Document/Revision/Element/Chunk/Projection Job；旧投影只按 Space 固化合同计算代际并可继续，不调用当前 Parser/Cleaner/Chunker |

## 7. 索引投影

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| IDX-001 | P0 | 关键词投影完成 | 文档写入后轮询 projection | KEYWORD 最终为成功 |
| IDX-002 | P0 | 向量投影完成 | 文档写入后轮询 projection | VECTOR 最终为成功，模型/维度/generation 正确 |
| IDX-003 | P1 | 投影幂等 | 对同一修订重复执行投影 | ES/Milvus 不产生重复有效记录 |
| IDX-004 | P1 | 投影失败重试 | 模拟外部 Provider 失败 | 进入退避重试，attempt 递增 |
| IDX-005 | P1 | 投影死信 | 连续超过最大重试次数 | 状态进入 `DEAD`，管理面可见 |
| IDX-006 | P1 | 人工重试 | 对失败投影调用 retry API | 创建/恢复任务并重新执行 |
| IDX-007 | P0 | 活动修订切换 | 新修订投影期间执行检索 | 旧修订结果被 ActiveRevisionGuard 拒绝；跨通道 generation 原子发布不在 Phase 1 范围 |
| IDX-008 | P1 | 空间级投影重建 | 对空间调用 `/api/v1/spaces/{spaceId}/projections/rebuild` | 只为本租户、本空间的活动修订重排已启用外部通道；不重置 RUNNING Job |
| IDX-009 | P1 | 无外部通道重建 | 默认关闭 ES/Milvus 时调用空间 rebuild | HTTP 200，返回 `jobs=0` 和空 `projectionTypes`，不返回 500 |
| IDX-010 | P0 | 历史修订挤占召回窗口 | 为同一文档制造多版高分旧修订，再查询当前版独有内容 | ES/Milvus 依次执行 1x/2x/4x 有界 overfetch，活动修订过滤后再截断；旧修订不得令首窗误返回空结果 |

## 8. RAG 检索与 Evidence

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| RET-001 | P0 | 唯一关键词检索 | 查询唯一检索词 | 命中公共测试文档 |
| RET-002 | P0 | 语义检索 | 使用不包含原词的同义表达查询 | Milvus 通道召回相关 Chunk |
| RET-003 | P0 | 混合检索 | 查询同时适合 BM25 与向量的内容 | 结果融合且无重复 Evidence |
| RET-004 | P1 | 精确错误码 | 查询专有标识/错误码 | 关键词通道优先命中 |
| RET-005 | P1 | 空查询 | 提交空白 query | HTTP 400 |
| RET-006 | P1 | topK 边界 | 测试 0、1、最大值、超最大值 | 合法边界接受，非法值 HTTP 400 |
| RET-007 | P1 | 空结果 | 查询不存在内容 | HTTP 200，Evidence 为空且 sufficient=false |
| RET-008 | P1 | 语言过滤 | 使用 `language=zh-CN` | 仅返回符合语言的候选 |
| RET-009 | P2 | 文档过滤（后续） | 指定 documentId | Phase 1 稳定拒绝未支持 Filter；实现后不返回其他文档 |
| RET-010 | P1 | 空间过滤 | 指定一个空间 | 不返回其他空间 |
| RET-011 | P0 | 引用完整性 | 检查 Evidence Bundle/Citation | Bundle 包含 tenant；Citation 包含 document、revision、chunk、section、sourceUri；请求空间边界可由 Trace 追溯 |
| RET-012 | P0 | 引用内容一致性 | 使用 citation 定位原 Chunk | Evidence 内容来自对应版本，不跨修订拼接 |
| RET-013 | P1 | requestId | 传入合法 `X-Request-Id` | 响应 Header 与 Body 使用同一 ID |
| RET-014 | P1 | 非法 requestId | 传入控制字符/超长 ID | 服务生成安全新 ID，不回显危险输入 |
| RET-015 | P1 | Trace 生成 | 成功检索后查询 traceId | Trace 含各通道耗时、候选数、最终数量 |
| RET-016 | P0 | Trace 不记录正文 | 查看数据库/API Trace | 不包含 query 正文、Chunk 正文或模型原始响应 |
| RET-017 | P1 | ES 不可用降级 | 暂停 ES 后检索 | 向量/数据库通道继续，返回稳定 warning |
| RET-018 | P1 | Milvus/GLM 不可用降级 | 暂停向量通道后检索 | 关键词通道继续，返回稳定 warning |
| RET-019 | P0 | 全通道失败 | 所有 Retriever 失败 | 返回受控错误/不足结果，不伪造答案 |
| RET-020 | P1 | 并发与背压 | 并发超过线程池和队列容量 | 有界拒绝/背压，无 OOM、无线程无限增长 |

## 9. 管理控制台

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| UI-001 | P0 | Dashboard | 管理员打开首页 | 指标卡和趋势/状态区域正常，无 API 错误 |
| UI-002 | P1 | 空间列表 | 打开知识空间页面 | 数据与 API 一致，加载/空/错误状态完整 |
| UI-003 | P0 | 创建空间 | 在页面选择 Parser、Cleaner、Chunker、Tokenizer 后创建空间 | 成功提示并刷新列表；请求携带完整配置，失败时保留用户输入 |
| UI-004 | P1 | 文档列表 | 打开文档页面并筛选 | 列表、状态、修订、投影信息正确 |
| UI-005 | P1 | 文档上传 | 使用页面提交 Markdown | 成功后显示文档及投影状态 |
| UI-006 | P1 | Chunk Inspector | 打开文档 Chunk | 顺序、章节、内容及 metadata 正确 |
| UI-007 | P0 | Retrieval Lab | 输入 query 并检索 | 显示 Evidence、分数、引用、warning、traceId |
| UI-008 | P1 | Trace 页面 | 打开 Trace 列表和详情 | 数据、步骤、耗时与 API 一致 |
| UI-009 | P1 | Connector 页面 | 打开连接器管理 | 列表、配置、同步状态可见 |
| UI-010 | P1 | Evaluation 页面 | 打开评测中心 | 数据集、Cases、Runs 和指标正常 |
| UI-011 | P1 | API 错误呈现 | 制造 400/403/500 | 页面显示可理解错误和 requestId，不白屏 |
| UI-012 | P1 | Token 刷新 | 页面保持超过 Token 刷新周期 | 会话继续，API 不持续 401 |
| UI-034 | P1 | 原文件与生命周期 | 上传 PDF/DOCX，查看原文件并归档、删除、恢复 | 元数据/下载/安全预览可用；状态和乐观版本正确；非活动文档不再检索 |
| UI-035 | P1 | 修订比较 | 为同一文档创建两个修订并打开历史 | 修订列表、活动标识、指定修订 Chunk 和双版本对照正确 |
| UI-036 | P1 | Graph Explorer | 打开 Graph 页面并搜索关系 | 只显示当前租户/授权空间的 Edge 和 Provenance |
| UI-037 | P1 | Wiki 管理 | 编译、送审、发布和归档页面 | 状态机、expectedVersion 和来源明细正确 |
| UI-038 | P1 | Audit 页面 | 执行变更后打开审计页面 | 只显示当前租户变更事件，不包含 Body/Token/Query/具体 URI |
| UI-013 | P2 | 路由刷新 | 在各二级路由刷新浏览器 | 页面可恢复，不返回服务器 404 |
| UI-014 | P2 | 响应式布局 | 测试常见桌面及窄屏宽度 | 核心操作可用，无严重遮挡 |
| UI-015 | P2 | 键盘与可访问性 | 使用 Tab/Enter 操作主要流程 | 焦点可见，表单有可识别 Label |
| UI-016 | P0 | 文档管理页面首次加载 | 管理员直接打开 `/documents`，不提供筛选条件 | 文档列表 API 返回 200，页面显示数据或空状态，不出现通用错误 |
| UI-039 | P0 | 数据抽取工作台 | 从 Space 卡片进入数据抽取页 | 能创建 TEST_ONLY/INGEST、轮询终态、查看逐文件身份/诊断/预览/Gate 并下载原件 |
| UI-040 | P1 | 文档处理能力可见性 | 打开创建 Space 页面和已有 Space 的只读配置 | 显示所有已知 Parser、Chunker 和 Tokenizer；不可用项禁用并显示原因，不把能力目录数量误报为可用数量 |
| UI-041 | P0 | 本次测试配置 | 在测试广场调整参数并运行，再返回查看 Space 配置 | 请求使用 `testConfig`；结果保存完整快照；Space 配置保持创建值且页面没有保存/提升入口 |

## 10. 评测体系

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| EVAL-001 | P1 | 创建数据集 | 创建评测数据集 | 数据集持久化并显示 |
| EVAL-002 | P1 | 添加 Case | 添加 query、空间和期望文档 | Case 持久化且计数更新 |
| EVAL-003 | P1 | 非法 Case | 缺少 query/space 或 topK 非法 | HTTP 400，前端显示错误 |
| EVAL-004 | P1 | 启动评测 | 对含 Case 的数据集启动 Run | 异步返回 Run，状态最终结束 |
| EVAL-005 | P1 | 指标正确性 | 构造命中与未命中 Case | Recall@K、MRR、nDCG 与人工计算一致 |
| EVAL-006 | P1 | 单 Case 失败隔离 | 让其中一个 Case 检索失败 | Run 保留，失败 Case 单独记录 |
| EVAL-007 | P1 | Trace 关联 | 查看成功 Case | 保存对应 traceId |
| EVAL-008 | P1 | 重复运行 | 同数据集连续运行两次 | 产生两个独立 Run，可比较指标 |
| EVAL-009 | P2 | 空数据集运行 | 对无 Case 数据集运行 | 拒绝运行或生成明确空结果，不永久 RUNNING |
| EVAL-010 | P2 | 运行中刷新 | Run 执行中刷新页面 | 状态可恢复，不丢失任务 |
| EVAL-011 | P1 | Baseline 质量门禁 | 选择同数据集两个成功 Run 并配置绝对阈值/最大回退 | 返回指标、delta、passed 和稳定 violation |
| EVAL-012 | P0 | 重启恢复与围栏 | Run 为 PENDING/RUNNING 时重启或由第二实例竞争 | PENDING/过期租约可恢复；旧 Worker 无权提交；Case/Principal 快照不变 |

## 11. Obsidian 连接器

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| CONN-001 | P1 | 创建合法连接器 | 使用白名单内 Vault 路径 | 配置持久化，状态可见 |
| CONN-002 | P0 | 路径越界 | 使用白名单外路径 | HTTP 400/403，不读取服务器任意文件 |
| CONN-003 | P0 | 路径穿越 | 使用 `..`、符号链接等越界路径 | 被规范化后拒绝 |
| CONN-004 | P1 | 全量同步 | 启动同步 | Run 异步执行并最终成功 |
| CONN-005 | P1 | Frontmatter | 同步含 YAML Frontmatter 文件 | metadata、标签和正文正确保存 |
| CONN-006 | P1 | WikiLink | 同步含 WikiLink 文档 | 链接信息保留，不破坏正文 |
| CONN-007 | P1 | 内容幂等 | 对未变化 Vault 重复同步 | 不创建重复修订 |
| CONN-008 | P1 | 单文件超限 | 文件超过配置大小 | 单文件受控失败/跳过，Run 记录原因 |
| CONN-009 | P1 | 同步失败恢复 | 同步中制造文件读取错误 | Run 失败可诊断，Checkpoint 不错误推进 |
| CONN-010 | P1 | 删除/移动同步 | 删除或移动已同步文件后再成功完成一轮全量同步 | 上轮 manifest 中缺失的文档变为 `ARCHIVED`；ActiveRevisionGuard 立即阻止各检索通道返回旧证据；移动按旧文档归档、新文档创建处理 |
| CONN-011 | P1 | Run 状态与租户隔离 | 通过启动响应的 runId 轮询 `/api/v1/connectors/runs/{runId}`，再用其他租户查询 | 返回终态、计数、错误码和起止时间；其他租户不可见 |
| CONN-012 | P0 | 半轮扫描失败不误删 | 第一轮同步成功，第二轮只扫描部分文件后制造解析失败或租约丢失 | 当前 manifest 与未扫描文档生命周期均不改变；暂存快照不被提升 |
| CONN-013 | P0 | 失败快照游标隔离 | 失败轮次持久化非零游标后创建新 run/snapshot | 新 snapshot 从初始游标开始，不复用失败 snapshot 的分页位置 |
| CONN-014 | P0 | 重启恢复与围栏 | Run 为 PENDING/RUNNING 时重启或由第二实例竞争 | PENDING/过期租约可恢复；只有有效租约可提升 Manifest/提交终态 |
| CONN-015 | P0 | Snapshot staging 崩溃窗口 | `stageManifest` 成功但 `saveCheckpoint` 前崩溃，期间删除该文件，再由新 lease 恢复 | V14 按 lease token 幂等清理旧 staging/cursor/counters 并从头扫描；同 token 重试和旧 token 不得清新数据 |
| CONN-016 | P0 | 批内逐记录租约围栏 | 第一条写入后让旧 Worker 失租，再由新 Worker 接管 | 每条记录写入前 heartbeat；旧 Worker 在下一条写入前停止，不提交 checkpoint/manifest/终态 |

## 12. 可观测性与错误契约

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| OBS-001 | P1 | Prometheus 认证 | 无 Token 请求 `/actuator/prometheus` | 按设计返回 401/403 |
| OBS-002 | P1 | Prometheus 基础指标 | 管理员请求 Prometheus | HTTP 200，含 HTTP/JVM 和低基数检索业务指标 |
| OBS-003 | P1 | requestId 贯通 | 发起进入应用层的带 requestId 失败请求，并单独触发安全链 401/403 | 应用层 Header、错误体和日志使用同一 ID；安全链直接响应至少保证 Header |
| OBS-004 | P0 | 敏感数据日志 | 执行登录、写入、检索和异常流程 | 日志不包含 Token、API Key、正文和模型原始响应 |
| OBS-005 | P1 | 500 错误格式 | 触发未预期异常 | 返回稳定错误码、requestId，无堆栈 |
| OBS-006 | P1 | 投影日志 | 制造投影重试/死信 | 日志含 job/tenant/document/type/attempt，不含正文 |
| OBS-007 | P2 | 指标基数 | 检查 Prometheus Labels | 不使用 documentId、userId、traceId 等高基数字段 |

## 13. 明确规划边界

以下能力当前应记录为 `NOT_SUPPORTED`，页面、API 和文档不得伪装为已完成：

| ID | 能力 | 当前预期 |
|---|---|---|
| PLAN-001 | Excel/PPT/图片 OCR | 未实现 |
| PLAN-002 | Query Decomposition、多跳、父子相邻 Chunk | 未实现；Query Rewrite/Multi-query 已作为可选能力实现 |
| PLAN-009 | 第三方 Chunker与动态 options Schema | 未实现；Provider/Token Counter 注册链已具备 |
| PLAN-010 | 目标 Embedding Serving Tokenizer 配对 | 未配置；本地固定 Tokenizer Adapter 已验收 |
| PLAN-003 | Owner/有效期/保密等级/组织审核完整治理 | 未实现 |
| PLAN-004 | Wiki Claim/Link/Diff/回滚/影响分析/自动重编译 | 未实现 |
| PLAN-005 | Graph 人工标注集和 Entity/Relation/Provenance 指标 | 未实现 |
| PLAN-006 | 最终答案生成、忠实度与引用完整性评测 | 未实现 |
| PLAN-007 | 第二个真实 Connector、定时源发现与通用市场 | 未实现 |
| PLAN-008 | 配额/限流、备份恢复、HA、完整 SLO/告警和跨服务 OTel | 未实现 |

## 14. 缺陷记录模板

```text
缺陷 ID：
关联用例：
严重级别：Blocker / Critical / Major / Minor
标题：
环境：
前置条件：
复现步骤：
实际结果：
预期结果：
证据：requestId / traceId / HTTP 响应 / 截图 / 日志位置
影响范围：
初步原因：
建议方案：
是否阻断验收：
```

## 15. 本轮审计新增回归用例

这些用例来自 2026-08-03～2026-08-11 的实际验收与代码审计，后续修复对应
缺陷时应优先自动化。

### 15.1 身份、修订与投影一致性

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| CONS-001 | P0 | 相同外部 ID 跨空间写入 | 在空间 A、B 使用同一 `externalId` 写入不同内容 | 生成两个空间隔离的文档身份；连接器、文档、Chunk 和检索结果均不串空间 |
| CONS-002 | P0 | A→B→A 修订回滚 | 同一文档依次写入内容 A、B、A | 第三次激活 A 对应修订或创建等价新修订；活动 Chunk、索引与 API 返回一致 |
| CONS-003 | P0 | 并发修订号分配 | 并发写入同一文档的不同内容 | 修订号唯一且顺序确定，无唯一键冲突、500 或活动版本丢失 |
| CONS-004 | P0 | ES 投影逆序完成 | 让新修订 B 先于旧修订 A 完成，再让 A 完成 | 旧任务不得覆盖/删除 B；检索守卫只返回当前活动修订 B，物理旧投影清理由后续对账负责 |
| CONS-005 | P0 | Milvus 旧修订隔离 | 文档 A→B 更新后分别搜索 A、B 独有语义 | 只返回当前活动修订 B，A 的向量不可见 |
| CONS-006 | P0 | 归档/删除后的全通道拒绝 | 索引文档后归档或删除 | Keyword/Vector/Graph/Page 均不得返回其证据；管理面仍可按权限查看保留修订/原文件 |
| CONS-007 | P0 | ACL 变更跨索引传播 | 先授权检索，再撤权 | 所有检索通道立即或在有界窗口内停止返回该知识 |
| CONS-008 | P1 | 后启 Adapter 历史回填 | 在 ES/Milvus 关闭时写入文档，再启用 Adapter | 系统提供可观测的回填/重建流程，历史文档最终可从新通道检索 |
| CONS-009 | P0 | 投影租约 Heartbeat 与围栏 | 单任务执行时间接近/超过租约，存在多个 Worker | Heartbeat 延长租约；过期 Worker 的 complete/fail 被拒绝 |
| CONS-010 | P1 | 跨通道过滤契约 | 使用 Phase 1 支持的 `sourceType`、`language` | PostgreSQL、Elasticsearch、Milvus 均执行相同的精确匹配，只返回同时满足过滤条件的候选 |
| CONS-011 | P0 | 同正文投影元数据更新 | 保持正文不变，仅修改标题、来源 URI、权威等级或元数据 | 复用原修订但重新排队外部投影；Citation 与排序字段最终为新值 |
| CONS-012 | P0 | 归档文档同正文恢复 | 归档当前文档后，以相同正文重新发布 | 文档恢复 ACTIVE，复用原修订并重新排队投影 |
| CONS-013 | P0 | 修订完整指纹 | 保持正文不变，改变 language 或 parser/chunker 处理契约，并验证 media type 规范化 | 真正的处理契约变化创建独立修订；Markdown media type 大小写变体不创建伪修订 |
| CONS-014 | P1 | 文档版本与时间单调 | 只改投影元数据，再执行 A→B→A 历史恢复 | 每次有效聚合变更只递增一次 version；`updated_at` 不倒退 |
| CONS-015 | P0 | RUNNING 投影期间元数据变更 | Worker 读取旧元数据后更新同修订文档字段 | Job 标记 dirty/requeue，本轮结束后至少再次投影到最新元数据 |
| CONS-016 | P0 | Graph 旧边不可作为路径桥梁 | 第一跳命中活动边，第二跳只存在归档/删除/旧修订边，第三跳仍有活动边 | 管理查询和 Graph RAG 每跳先过滤活动修订；不得跨过旧边返回第三跳关系 |

### 15.2 鉴权、租户与 HTTP 契约

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| AUTH-020 | P0 | Reader 首次登录入驻 | 使用新建 reader 用户首次登录并访问可用空间 | Principal 自动/受控入驻，能列出被授权空间；未授权空间不可见 |
| AUTH-021 | P0 | Reader 控制台权限矩阵 | reader 遍历所有前端路由和操作 | 只显示允许的导航与操作；检索不依赖管理员接口 |
| AUTH-022 | P1 | JWT Audience | 使用同 Realm、非本 API audience 的 Token 请求 API | HTTP 401；只有包含受信 API audience 的 Token 可用 |
| AUTH-023 | P1 | 系统主体约束 | 普通公开 Client 注入 `system_principal` 声明 | 不获得系统权限；仅受控服务 Client 可成为系统主体 |
| AUTH-024 | P1 | 缺少必需 Claim | 使用缺少 `tenant_id` 或 `sub` 的 Token | HTTP 401，而不是普通业务参数 400 |
| AUTH-025 | P1 | 角色与用户配置升级 | 对已有 Keycloak 数据卷应用新版 Realm 配置 | Client、角色、用户、mapper 均幂等收敛，不依赖删除数据卷 |
| AUTH-026 | P0 | 协议 Mapper 幂等收敛 | 对同一已有数据卷连续执行两次 `keycloak-config`，再分别获取 Admin/Reader Token | Console/CLI 各只有一份 `tenant-id`、`departments`、`api-audience`；Token 含正确 tenant、department、API audience 和角色 |
| API-015 | P0 | 管理文档列表过滤组合 | 分别使用无过滤、仅 space、仅 status、两者同时请求 | 所有合法组合均 200，分页总数准确，不因 NULL 参数产生 500 |
| API-016 | P1 | requestId 单一来源 | 带合法/非法 requestId 请求检索和错误接口 | 所有响应 Header 使用规范化 ID；应用层检索响应、`ApiError`、Trace、MDC 复用该值；安全过滤链 401/403 只断言 Header |
| API-017 | P1 | HTTP DTO 稳定性 | 校验 Controller DTO、OpenAPI 与实际 JSON 响应 | ID 为约定标量；字段名（如 `generatedAt`）与前端类型/OpenAPI 一致 |
| API-018 | P0 | 来源 URI 协议白名单 | 写入 `javascript:`、`data:`、`file:` 及合法业务 URI | 危险协议在写入或响应映射前被拒绝；前端只渲染批准协议 |
| API-019 | P1 | Connector single-flight | 同一 Connector 已有 PENDING/RUNNING Run 时再次启动 | 返回 409 和 `OPERATION_IN_PROGRESS` |
| API-020 | P1 | 后台队列饱和 | 填满 Connector/Evaluation 有界执行队列后提交任务 | 返回 503 和 `WORK_QUEUE_SATURATED`，不泄露内部异常 |
| API-021 | P1 | Overview 投影统计 | 分别创建 PENDING、RETRY、RUNNING、SUCCEEDED、DEAD Job 后查询总览 | “处理中”统计前三种非终态，“死信”只统计 DEAD，不引用不存在状态 |
| API-022 | P1 | Markdown metadata 边界 | 提交空键/空值、超长键值或超过 64 项 metadata | HTTP 400 且不进入领域/存储层，不返回 500 |
| API-023 | P1 | 活动修订投影重试 | 同一文档保留两个修订的同类型 DEAD Job，查询投影并执行 retry | 管理 API 只展示和重排活动修订；历史修订保持 DEAD，不返回错误的 `requeued=false` |

### 15.3 前端、跨域与异步任务

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| UI-020 | P1 | Origin 配置矩阵 | 分别用 `localhost:5173`、`127.0.0.1:5173` 和 preview 地址运行 | Keycloak redirect/web origin 与 API CORS 一致；受支持地址均可登录并调用 API |
| UI-021 | P1 | Preview 配置 | 执行 `npm run preview` | 固定且文档化的端口可用；OIDC、CORS 和 API Base URL 均匹配 |
| UI-022 | P1 | 源码与构建产物一致性 | 构建后检查 Client ID、API 地址和版本指纹 | `dist` 与当前源码配置一致，不包含旧端口或旧 Client |
| UI-023 | P1 | 文档超过 100 条分页 | 创建至少 101 个文档并浏览列表 | 可访问全部文档；total、offset/page 和筛选正确 |
| UI-024 | P1 | Dashboard 部分接口失败 | 仅让 spaces/traces 请求失败 | 不显示伪造的 0/空数据；显示局部错误、重试和 requestId |
| UI-025 | P1 | 投影状态与重建 | 制造 DEAD projection，打开文档抽屉并从空间页触发 rebuild | 页面显示 Keyword/Vector/Graph 汇总、逐 Job attempt/错误与重建结果；仅 DEAD 任务提供精确 retry |
| UI-026 | P1 | Connector 长任务轮询 | 连接器同步持续超过 2 秒 | 页面持续轮询到终态；刷新页面后可恢复；运行中不可重复启动 |
| UI-027 | P1 | Evaluation 运行与结果展示 | 自定义 Run Top K 后运行评测并打开 Run 详情 | 展示 Hit Rate/Recall/MRR/nDCG、Case 结果数/耗时/状态/error；有 traceId 时可深链到对应 Trace |
| UI-028 | P1 | Evaluation 空目标校验 | 创建既无目标文档又无目标 Chunk 的 Case | 前端提交前阻止并给出说明；绕过前端调用 API 仍返回 400 和稳定错误契约 |
| UI-029 | P2 | 未知路由 | 打开不存在的前端路由 | 显示 404 页面，不白屏 |
| UI-030 | P2 | 懒加载/渲染失败 | 模拟动态 Chunk 加载失败或页面异常 | Error Boundary 提供恢复/刷新入口，不白屏 |
| UI-031 | P2 | Dialog 键盘与焦点 | 仅用键盘打开、操作和关闭 Modal/Drawer | 具备 dialog 语义、焦点圈、Esc 关闭和焦点恢复；点击面板标题不误关 |
| UI-032 | P1 | 文档投影重试状态隔离 | 文档 A 的重试处于 RUNNING/失败时切换到文档 B | B 不显示 A 的错误或重试动画；已有重试未结束时不重复提交另一条重试 |
| UI-033 | P1 | 评测运行数据集隔离 | 在数据集 A 启动 Run，响应前切换到 B 并选择 B 的 Run | A 的异步响应只刷新 A 的列表，不覆盖或显示为 B 的运行详情 |

### 15.4 可观测性、连接器与工程隔离

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| OBS-020 | P1 | 检索业务指标与基数 | 执行成功、降级、超时检索并查看 Prometheus | 指标记录总耗时/结果/通道状态；Label 不含 document/user/trace 等高基数值 |
| OBS-021 | P1 | 变更审计 | 执行成功/失败的 POST/PATCH/DELETE 并查询审计 API | 当前租户可分页查看 route pattern、主体、状态、耗时；不存 Body/Token/Query/具体 URI |
| CONN-020 | P0 | Windows Junction/符号链接越界 | 在白名单目录内放置指向外部的 Junction/符号链接并同步 | 通过真实路径校验拒绝越界，避免 TOCTOU |
| CONN-021 | P1 | 同一连接器并发同步 | 并发发起两次同步 | 只有一个有效 RUNNING Run；其他请求返回 409 |
| CONN-022 | P0 | 连接器运行恢复 | Run 创建为 PENDING/RUNNING 后立即重启服务 | PENDING/过期租约被低频恢复器接管，不永久挂起；旧 Worker 无权提交 |
| EVAL-020 | P0 | 评测运行恢复 | Run 创建为 PENDING/RUNNING 后立即重启服务 | PENDING/过期租约被低频恢复器接管，持久化 Case/Principal 快照不丢失 |
| BUILD-001 | P1 | Milvus 模块独立契约测试 | 单独执行 `store-milvus` 真实集成测试 | 类路径完整，无 `LoggerFactory` 等传递依赖缺失 |
| BUILD-002 | P1 | 默认构建与外部契约边界 | 执行默认 `verify`，再用显式环境开关执行外部 IT | 默认构建明确标注跳过项；外部 IT 覆盖 PG/ES/Milvus/MinIO/Neo4j/GLM 并生成独立报告 |

## 16. P2 知识层新增回归用例

这些用例已经进入当前实现范围，但当前报告不默认标记为 PASS；最终验收必须按
真实外部环境和浏览器执行。

### 16.1 富文档、原文件和生命周期

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| ASSET-001 | P0 | 文件类型与真实解析 | 分别上传 TXT/HTML/PDF/DOCX | 生成 Document/Revision/Element/Chunk，标题/文本可检索，原文件可授权下载 |
| ASSET-002 | P0 | 解析资源预算 | 上传超限 PDF、ZIP Bomb 型 DOCX、超页数/Element 文档 | 受控 400/413，不 OOM、不写入半成品事实 |
| ASSET-003 | P0 | 原文件 ACL 与租户 | Admin、授权 Reader、未授权 Reader、其他租户读取 source | 仅授权主体可读；响应不暴露 Bucket/Object Key |
| ASSET-004 | P1 | 安全预览 | 对安全/不安全媒体类型请求 `inline=true` | 安全类型带 sandbox/nosniff/no-store；其他类型强制 attachment |
| ASSET-005 | P0 | 生命周期并发 | 使用正确/错误 expectedVersion 归档、删除和恢复 | 正确版本原子转换；冲突 409；版本和时间单调 |
| ASSET-006 | P1 | 修订历史 | 创建多修订并查询指定 Revision Chunk | 历史不可变、active 标记唯一、跨文档 revisionId 被拒绝 |

### 16.2 Graph 与 Wiki

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| GRAPH-001 | P0 | Graph 投影幂等与来源 | 对同一活动修订重复投影 | Entity/Relation 不重复；每条边有 tenant/space/document/revision/chunk Provenance |
| GRAPH-002 | P0 | Graph 租户/ACL/活动修订 | 跨租户、撤权、A→B、归档后做关系查询 | 旧/越权关系不进入管理结果或 Evidence |
| GRAPH-003 | P1 | 图查询预算和注入 | maxHops 0/4、limit 0/201，并在 query 中放 Cypher 片段 | 非法预算 400；用户文本不作为 Cypher 执行 |
| GRAPH-004 | P1 | Graph 混合召回 | 用“依赖/调用/影响”问题查询 | 规划 Graph 通道并与 Keyword/Vector 融合，Citation 回到原 Chunk |
| WIKI-001 | P0 | 来源边界 | 使用其他租户、其他空间、旧修订或错误 Chunk 编译 | 请求被拒绝，不生成跨界页面 |
| WIKI-002 | P0 | 状态机与乐观版本 | 编译、送审、驳回、发布、归档并制造版本冲突 | 仅合法转换成功；冲突 409；未发布页面不参与检索 |
| WIKI-003 | P0 | Published Page Evidence | 发布页面后查询，再归档来源文档 | 返回活动原始 Chunk；来源非活动后页面不再产生 Evidence |
| WIKI-004 | P1 | 生成模式边界 | 默认模式和显式 GLM 模式分别编译 | 默认不调用外网；GLM 失败受控；source coverage/版本可追溯 |

### 16.3 评测、Agent 契约与 E2E

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| AGENT-001 | P0 | Java Client 契约 | 使用 Mock/真实 API 调用 `KnowledgeSearchTool` | Token、requestId、timeout、错误码和 Evidence DTO 映射稳定 |
| AGENT-002 | P1 | OpenAPI 一致性 | 校验 `docs/openapi.yaml` 并与运行响应比对 | 路径、状态码和核心 Schema 与实现一致 |
| E2E-001 | P0 | Admin 主路径 | 单 Worker Playwright 登录并完成空间、文件、检索、Wiki/Graph/Evaluation/Audit | 无白屏；关键 API/页面断言通过；失败保留截图/Trace |
| E2E-002 | P0 | Reader 权限路径 | Reader 登录并遍历导航、检索、原文件访问 | 仅可见授权能力；管理路由和跨空间数据不可见 |
| E2E-003 | P1 | 不自动启服 | 在服务未启动时运行 Playwright list/test | `--list` 不启服务；test 明确失败而不拉起 Docker/Java/浏览器下载 |
| E2E-004 | P0 | 抽取工作台 | 验证创建时固化配置，并真实运行多文件 TEST_ONLY、临时测试配置、精确 Tokenizer、取消、INGEST 成功/重复/冲突 | 相关串行场景全部读取真实 API；Space 配置保持不变，数据库、OSS、投影与页面结果一致 |
