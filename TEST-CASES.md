# Infinity Knowledge Runtime 功能测试用例

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档用途 | 维护前端、API、权限、检索、评测、连接器及可观测性回归用例 |
| 当前版本 | 1.1 |
| 基线日期 | 2026-08-09 |
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
| ENV-006 | P1 | 数据库迁移 | 查看后端启动日志与 Flyway 表 | V1–V7 均已成功，应用无待执行迁移 |

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
| ING-001 | P0 | 创建知识空间 | 管理员创建公共测试空间 | HTTP 204，管理列表可见 |
| ING-002 | P1 | 重复空间 ID | 使用相同 ID 再次创建 | 返回稳定冲突或幂等结果，不产生两条空间 |
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
| ING-017 | P1 | 处理契约变更 | 正文不变，修改 language、parser 版本或 Chunk 预算后重新发布；另用大小写变体提交 Markdown media type | 处理契约变化生成新的不可变修订和 Chunk；media type 被规范化，不因大小写产生伪修订 |

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
| UI-003 | P1 | 创建空间 | 在页面创建空间 | 成功提示并刷新列表 |
| UI-004 | P1 | 文档列表 | 打开文档页面并筛选 | 列表、状态、修订、投影信息正确 |
| UI-005 | P1 | 文档上传 | 使用页面提交 Markdown | 成功后显示文档及投影状态 |
| UI-006 | P1 | Chunk Inspector | 打开文档 Chunk | 顺序、章节、内容及 metadata 正确 |
| UI-007 | P0 | Retrieval Lab | 输入 query 并检索 | 显示 Evidence、分数、引用、warning、traceId |
| UI-008 | P1 | Trace 页面 | 打开 Trace 列表和详情 | 数据、步骤、耗时与 API 一致 |
| UI-009 | P1 | Connector 页面 | 打开连接器管理 | 列表、配置、同步状态可见 |
| UI-010 | P1 | Evaluation 页面 | 打开评测中心 | 数据集、Cases、Runs 和指标正常 |
| UI-011 | P1 | API 错误呈现 | 制造 400/403/500 | 页面显示可理解错误和 requestId，不白屏 |
| UI-012 | P1 | Token 刷新 | 页面保持超过 Token 刷新周期 | 会话继续，API 不持续 401 |
| UI-013 | P2 | 路由刷新 | 在各二级路由刷新浏览器 | 页面可恢复，不返回服务器 404 |
| UI-014 | P2 | 响应式布局 | 测试常见桌面及窄屏宽度 | 核心操作可用，无严重遮挡 |
| UI-015 | P2 | 键盘与可访问性 | 使用 Tab/Enter 操作主要流程 | 焦点可见，表单有可识别 Label |
| UI-016 | P0 | 文档管理页面首次加载 | 管理员直接打开 `/documents`，不提供筛选条件 | 文档列表 API 返回 200，页面显示数据或空状态，不出现通用错误 |

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
| CONN-010 | P2 | 删除/移动同步 | 删除或移动已同步文件后再同步 | 当前版本标记 `NOT_SUPPORTED`，不得宣称已清理索引 |
| CONN-011 | P1 | Run 状态与租户隔离 | 通过启动响应的 runId 轮询 `/api/v1/connectors/runs/{runId}`，再用其他租户查询 | 返回终态、计数、错误码和起止时间；其他租户不可见 |

## 12. 可观测性与错误契约

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| OBS-001 | P1 | Prometheus 认证 | 无 Token 请求 `/actuator/prometheus` | 按设计返回 401/403 |
| OBS-002 | P1 | Prometheus 基础指标 | 管理员请求 Prometheus | HTTP 200，含 HTTP/JVM 等框架指标；检索和任务业务指标由 OBS-020 跟踪 |
| OBS-003 | P1 | requestId 贯通 | 发起进入应用层的带 requestId 失败请求，并单独触发安全链 401/403 | 应用层 Header、错误体和日志使用同一 ID；安全链直接响应至少保证 Header |
| OBS-004 | P0 | 敏感数据日志 | 执行登录、写入、检索和异常流程 | 日志不包含 Token、API Key、正文和模型原始响应 |
| OBS-005 | P1 | 500 错误格式 | 触发未预期异常 | 返回稳定错误码、requestId，无堆栈 |
| OBS-006 | P1 | 投影日志 | 制造投影重试/死信 | 日志含 job/tenant/document/type/attempt，不含正文 |
| OBS-007 | P2 | 指标基数 | 检查 Prometheus Labels | 不使用 documentId、userId、traceId 等高基数字段 |

## 13. 明确规划边界

以下能力当前应记录为 `NOT_SUPPORTED`，页面、API 和文档不得伪装为已完成：

| ID | 能力 | 当前预期 |
|---|---|---|
| PLAN-001 | Neo4j Graph Retrieval | 未实现 |
| PLAN-002 | LLM Wiki Compiler | 未实现 |
| PLAN-003 | PDF/DOCX/HTML 结构解析 | 未实现 |
| PLAN-004 | MinIO 原文件体系 | 未实现 |
| PLAN-005 | 删除/ACL 跨索引原子传播 | 未完成 |
| PLAN-006 | 生成答案忠实度与引用评测 | 未实现 |
| PLAN-007 | 分布式 Connector 定时调度 | 未实现 |
| PLAN-008 | OpenTelemetry 跨服务导出 | 未实现 |

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

这些用例来自 2026-08-03～2026-08-09 的实际验收与代码审计，后续修复对应
缺陷时应优先自动化。

### 15.1 身份、修订与投影一致性

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| CONS-001 | P0 | 相同外部 ID 跨空间写入 | 在空间 A、B 使用同一 `externalId` 写入不同内容 | 生成两个空间隔离的文档身份；连接器、文档、Chunk 和检索结果均不串空间 |
| CONS-002 | P0 | A→B→A 修订回滚 | 同一文档依次写入内容 A、B、A | 第三次激活 A 对应修订或创建等价新修订；活动 Chunk、索引与 API 返回一致 |
| CONS-003 | P0 | 并发修订号分配 | 并发写入同一文档的不同内容 | 修订号唯一且顺序确定，无唯一键冲突、500 或活动版本丢失 |
| CONS-004 | P0 | ES 投影逆序完成 | 让新修订 B 先于旧修订 A 完成，再让 A 完成 | 旧任务不得覆盖/删除 B；检索守卫只返回当前活动修订 B，物理旧投影清理由后续对账负责 |
| CONS-005 | P0 | Milvus 旧修订隔离 | 文档 A→B 更新后分别搜索 A、B 独有语义 | 只返回当前活动修订 B，A 的向量不可见 |
| CONS-006 | P2 | 归档/删除后的全通道对账（后续） | 索引文档后归档或删除 | Phase 1 尚无完整生命周期 API 和跨存储对账，记录为 `NOT_SUPPORTED`；实现后所有通道均不得返回其证据 |
| CONS-007 | P0 | ACL 变更跨索引传播 | 先授权检索，再撤权 | 所有检索通道立即或在有界窗口内停止返回该知识 |
| CONS-008 | P1 | 后启 Adapter 历史回填 | 在 ES/Milvus 关闭时写入文档，再启用 Adapter | 系统提供可观测的回填/重建流程，历史文档最终可从新通道检索 |
| CONS-009 | P2 | 投影租约续期与围栏（后续） | 单任务执行时间超过租约，存在多个 Worker | Phase 1 记录为已知 A-006；实现后过期 Worker 无权提交 |
| CONS-010 | P1 | 跨通道过滤契约 | 使用 Phase 1 支持的 `sourceType`、`language` | PostgreSQL/ES 执行相同语义；Milvus 明确降级并返回 Warning，不静默忽略 |
| CONS-011 | P0 | 同正文投影元数据更新 | 保持正文不变，仅修改标题、来源 URI、权威等级或元数据 | 复用原修订但重新排队外部投影；Citation 与排序字段最终为新值 |
| CONS-012 | P0 | 归档文档同正文恢复 | 归档当前文档后，以相同正文重新发布 | 文档恢复 ACTIVE，复用原修订并重新排队投影 |
| CONS-013 | P0 | 修订完整指纹 | 保持正文不变，改变 language 或 parser/chunker 处理契约，并验证 media type 规范化 | 真正的处理契约变化创建独立修订；Markdown media type 大小写变体不创建伪修订 |
| CONS-014 | P1 | 文档版本与时间单调 | 只改投影元数据，再执行 A→B→A 历史恢复 | 每次有效聚合变更只递增一次 version；`updated_at` 不倒退 |
| CONS-015 | P2 | RUNNING 投影期间元数据变更（后续） | Worker 读取旧元数据后更新同修订文档字段 | Phase 1 记录为 A-006 围栏缺口；实现后必须至少再次投影到最新元数据 |

### 15.2 鉴权、租户与 HTTP 契约

| ID | 优先级 | 测试场景 | 步骤 | 预期结果 |
|---|---:|---|---|---|
| AUTH-020 | P0 | Reader 首次登录入驻 | 使用新建 reader 用户首次登录并访问可用空间 | Principal 自动/受控入驻，能列出被授权空间；未授权空间不可见 |
| AUTH-021 | P0 | Reader 控制台权限矩阵 | reader 遍历所有前端路由和操作 | 只显示允许的导航与操作；检索不依赖管理员接口 |
| AUTH-022 | P1 | JWT Audience | 使用同 Realm、非本 API audience 的 Token 请求 API | HTTP 401；只有包含受信 API audience 的 Token 可用 |
| AUTH-023 | P1 | 系统主体约束 | 普通公开 Client 注入 `system_principal` 声明 | 不获得系统权限；仅受控服务 Client 可成为系统主体 |
| AUTH-024 | P1 | 缺少必需 Claim | 使用缺少 `tenant_id` 或 `sub` 的 Token | HTTP 401，而不是普通业务参数 400 |
| AUTH-025 | P1 | 角色与用户配置升级 | 对已有 Keycloak 数据卷应用新版 Realm 配置 | Client、角色、用户、mapper 均幂等收敛，不依赖删除数据卷 |
| API-015 | P0 | 管理文档列表过滤组合 | 分别使用无过滤、仅 space、仅 status、两者同时请求 | 所有合法组合均 200，分页总数准确，不因 NULL 参数产生 500 |
| API-016 | P1 | requestId 单一来源 | 带合法/非法 requestId 请求检索和错误接口 | 所有响应 Header 使用规范化 ID；应用层检索响应、`ApiError`、Trace、MDC 复用该值；安全过滤链 401/403 只断言 Header |
| API-017 | P1 | HTTP DTO 稳定性 | 校验 Controller DTO 单元测试与实际 JSON 响应 | ID 为约定标量；字段名（如 `generatedAt`）与前端类型一致；OpenAPI 生成不作为 Phase 1 前置条件 |
| API-018 | P0 | 来源 URI 协议白名单 | 写入 `javascript:`、`data:`、`file:` 及合法业务 URI | 危险协议在写入或响应映射前被拒绝；前端只渲染批准协议 |
| API-019 | P1 | Connector single-flight | 同一 Connector 已有 RUNNING Run 时再次启动 | 返回 409 和 `OPERATION_IN_PROGRESS` |
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
| UI-025 | P1 | 投影状态与重建 | 制造 DEAD projection，打开文档抽屉并从空间页触发 rebuild | 页面显示 Keyword/Vector 汇总、逐 Job attempt/错误与重建结果；仅 DEAD 任务提供精确 retry |
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
| OBS-020 | P2 | 业务指标完整性（后续） | 执行检索、投影、连接器和评测成功/失败流程 | 在基础 Actuator/Prometheus 之上增加有界基数的队列、通道和运行指标 |
| CONN-020 | P0 | Windows Junction/符号链接越界 | 在白名单目录内放置指向外部的 Junction/符号链接并同步 | 通过真实路径校验拒绝越界，避免 TOCTOU |
| CONN-021 | P1 | 同一连接器并发同步 | 并发发起两次同步 | 只有一个有效 RUNNING Run；其他请求返回 409 |
| CONN-022 | P2 | 连接器运行恢复（后续） | Run 创建为 RUNNING 后立即重启服务 | Phase 1 记录为不支持；实现后任务继续或明确失败，不永久挂起 |
| EVAL-020 | P2 | 评测运行恢复（后续） | Run 创建为 RUNNING 后立即重启服务 | Phase 1 记录为不支持；实现后任务继续或明确失败，不永久挂起 |
| BUILD-001 | P1 | Milvus 模块独立契约测试 | 单独执行 `store-milvus` 真实集成测试 | 类路径完整，无 `LoggerFactory` 等传递依赖缺失 |
| BUILD-002 | P1 | 默认构建与外部契约边界 | 执行默认 `verify`，再用显式环境开关执行外部 IT | 默认构建明确标注跳过项；外部 IT 覆盖 PG/ES/Milvus/GLM 并生成独立报告 |
