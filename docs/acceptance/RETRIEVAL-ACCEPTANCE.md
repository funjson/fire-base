# 企业检索升级验收规范

## 1. 验收目标与事实边界

本规范验收 Space 检索配置、单次请求覆盖、固定优化 Chain、模型 Space 排序、逐层事件、
Coverage、Reranker 全量回退、测试广场指标隔离和按请求下钻观测。检索主链自动化验收使用
受控 Stub，不消耗外部模型；真实 Elasticsearch、Milvus 和模型服务只在受控环境集中验收。

必须区分两类指标：

- 运行指标来自逐层事件，可计算耗时、候选数、分支成功率、融合重复率、Coverage 分数、
  Chain 增量、模型请求数和降级情况；这些指标不依赖 Gold 标注。
- `Recall@K`、MRR、`nDCG@K` 等质量指标只能由带 `expectedDocuments` 或
  `expectedChunks` 的固定 Dataset 计算。不能根据在线候选分数、Coverage 分数或结果数推测质量。

当前 `RetrievalEvaluationCase` 至少需要一个相关 Document/Chunk 标签，因此“不可回答”用例
只能通过知识查询 API 验收，不能直接交给 `RetrievalEvaluationRunner`。本目录的 fixture 已把
该用例放在 `acceptanceOnlyCases`，没有伪造 Gold 标签。

## 2. 验收资料

```text
docs/acceptance/
├── RETRIEVAL-ACCEPTANCE.md
└── fixtures/
    ├── retrieval-evaluation-v1.json
    ├── retrieval-engineering.md
    └── retrieval-operations.md
```

`retrieval-evaluation-v1.json` 是当前数据结构的明确 fixture，不存在自动 JSON Loader：

- `runnerCases.id/query/expectedDocuments/expectedChunks` 对齐
  `RetrievalEvaluationCase`；运行时 `PrincipalContext` 必须由测试装配或 HTTP JWT 注入。
- `${ENGINEERING_DOCUMENT_ID}` 和 `${OPERATIONS_DOCUMENT_ID}` 必须替换为本次摄取产生的真实 UUID。
- 自动 Runner 计算口径由 `RetrievalEvaluationRunnerTest` 覆盖；固定 Chain、Coverage、跨 Space
  和回退行为由 `RetrievalMainChainAcceptanceTest` 覆盖。
- `acceptanceOnlyCases` 中的不可回答用例只能走 `POST /api/v1/knowledge/query`。

## 3. 环境准备

受控全链验收需要 PostgreSQL、Keycloak、Elasticsearch、Milvus、MinIO 和可选模型服务：

```powershell
docker compose --profile acceptance up -d --wait --wait-timeout 300 `
  postgres keycloak etcd minio milvus elasticsearch neo4j
docker compose --profile acceptance run --rm keycloak-config

if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY)) {
  throw '真实模型验收需要 ZHIPU_API_KEY；纯自动化验收不需要'
}

$env:KNOWLEDGE_SPACE_ROUTER_ENABLED = 'true'
$env:KNOWLEDGE_COVERAGE_JUDGE_ENABLED = 'true'
$env:KNOWLEDGE_FEEDBACK_PLANNER_ENABLED = 'true'
$env:KNOWLEDGE_RERANKER_ENABLED = 'true'

if ([string]::IsNullOrWhiteSpace($env:KNOWLEDGE_RETRIEVAL_FINGERPRINT_SECRET)) {
  throw '受控验收必须由秘密管理系统注入至少 32 字节的查询指纹密钥'
}
$env:KNOWLEDGE_RETRIEVAL_FINGERPRINT_KEY_VERSION = 'acceptance-v1'

.\mvnw.cmd -T1 -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=acceptance
```

若本机需要代理，同时设置对应的 `KNOWLEDGE_*_PROXY_HOST/PORT`。不要在日常开发中反复启动
完整环境或批量调用模型。只验收确定性主链时可以关闭四个模型开关，并执行第 13 节的聚焦测试。

按 [API 验收说明](../API.md) 获取管理员 Token，然后保留后续命令需要的变量：

```powershell
$headers = @{
  Authorization  = "Bearer $($token.access_token)"
  'X-Request-Id' = [Guid]::NewGuid().ToString()
}
$engineering = 'engineering'
$operations = 'operations'
```

创建两个 Space 后，分别上传 `retrieval-engineering.md` 和 `retrieval-operations.md`。上传走正式
多文件摄取入口，完成后从 Run 的 `items[].documentId` 记录：

```text
POST /api/v1/spaces/engineering/ingestion-runs
POST /api/v1/spaces/operations/ingestion-runs
GET  /api/v1/spaces/{spaceId}/extraction-runs/{runId}
```

只有 Run 为 `SUCCEEDED` 且 Keyword/Vector 投影均成功后，才能把两个真实 Document UUID 写入
评测 Dataset。投影尚未完成时出现空召回不属于检索策略质量结论。

文档修订正式发布时会在同一数据库事务内创建或校验 Space 的真实基线索引代际，因此默认仅启用
PostgreSQL 关键词通道时也可以检索，不依赖 Elasticsearch 或 Milvus Worker 顺带创建代际。验收时可
查询 `index_generation`：每个已发布文档的 Space 必须恰有一个 `ACTIVE` 代际；文档事务回滚时不得
留下孤立代际。

检索读取活动代际前会用投影侧同一个权威算法，重新计算 Embedding、物理 generation、Normalizer
和 Chunker 的合同指纹。启用 Elasticsearch 时，实际 index-name 与完整 Mapping 的 SHA-256 指纹也
属于同一物理合同；修改索引名或 Mapping 但不重建 Space 数据后同样必须拒绝查询。受控修改任一部署
合同但不重建 Space 数据后，查询必须在物理召回前失败并产生 `CONFIGURATION_RESOLVED_FAILED` 与
技术失败终态，不能查询新集合却上报旧活动版本。当前版本采用这种 fail-closed 策略；未来多模型、
多代际切换需要把物理目标纳入持久化代际快照后再开放。

## 4. Space 检索配置

Space 创建时会物化确定性基线配置；系统启动默认值以后变化，不会静默改变已有 Space。

```powershell
$current = Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/spaces/$engineering/retrieval-configuration" `
  -Headers $headers

$history = Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/spaces/$engineering/retrieval-configuration/history?limit=20" `
  -Headers $headers
```

验收断言：

- `revision >= 1`，`fingerprint` 为 64 位小写十六进制；
- 配置完整包含 `firstRound/branches/reranker/coverage/maximumRetrievalAttempts/
  chainNodeEnables/crossSpace`；
- 四个召回通道和七个 Chain 节点均有物化值；
- 历史按修订号倒序，旧修订内容不被覆盖。

更新必须提交完整配置和页面刚读取的 `expectedRevision`：

```powershell
$config = $current.configuration
$config.coverage.enabled = $true
$config.coverage.providerId = 'zhipu'
$config.coverage.modelId = 'glm-4.5-flash'
$config.coverage.promptVersion = 'coverage-v1'
$config.maximumRetrievalAttempts = 4
$config.chainNodeEnables.GAP_QUERY = $true
$config.chainNodeEnables.PRF = $true

$body = @{
  expectedRevision = $current.revision
  configuration    = $config
} | ConvertTo-Json -Depth 12

$updated = Invoke-RestMethod `
  -Method Put `
  -Uri "http://localhost:8080/api/v1/spaces/$engineering/retrieval-configuration" `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

验收断言：新语义配置使 `revision` 加一并产生新 `fingerprint`；重复提交相同语义不产生无意义修订；
使用过期 `expectedRevision` 且内容不同返回 409，错误码为
`SPACE_RETRIEVAL_CONFIGURATION_REVISION_CONFLICT`。索引代际、Embedding 维度和 Tokenizer
不属于该可变配置；需要改变索引合同应创建新 Space。

## 5. 单次请求覆盖与配置指纹

请求只发送需要覆盖的强类型字段，未出现的字段继承当前 Space 配置；覆盖结果仍受部署硬上限约束，
超限必须返回 400，不能静默裁剪。

```powershell
$before = Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/spaces/$engineering/retrieval-configuration" `
  -Headers $headers

$body = @{
  query = 'IKR-RET-042 在 buildEvidenceBundle 阶段表示什么？'
  spaceIds = @($engineering)
  topK = 5
  testMode = $true
  configurationOverride = @{
    branches = @{
      maximumRetrievalBranches = 2
      channels = @{
        GRAPH = @{ enabled = $false }
        PAGE  = @{ enabled = $false }
      }
    }
    reranker = @{ enabled = $false }
    coverage = @{ enabled = $false }
    maximumRetrievalAttempts = 1
  }
} | ConvertTo-Json -Depth 12

$result = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/knowledge/query' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))

$after = Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/spaces/$engineering/retrieval-configuration" `
  -Headers $headers
```

验收断言：`before.revision == after.revision` 且持久化指纹不变；响应
`configurationFingerprints` 记录本次真实有效配置指纹；终态为 `NOT_EVALUATED` 且停止原因是
`COVERAGE_DISABLED`。请求覆盖不得写回 Space。

## 6. 固定 Chain 与反馈优化

权威顺序固定为：

```text
GAP_QUERY -> PRF -> RELAX_CONSTRAINTS -> NARROW_CONSTRAINTS
          -> STEP_BACK -> HYDE -> NEXT_SPACE
```

Space 或请求只能开关节点，不能传入顺序。打开多个节点并让 Coverage 首轮判定不足后，按
`requestId` 查询观测：`CHAIN_NODE_EVALUATED` 与 `CHAIN_NODE_COMPLETED` 的 sequence 必须递增；
`metrics[].dimensions.strategy` 给出节点策略，实际完成顺序必须是上述顺序的子序列。
`retrieval.chain.node.coverage.delta` 只有前后 Coverage 都存在时才产生，缺失值不能补零。

模型失败、节点前置条件不满足或检索预算耗尽时，必须有稳定 `status/reasonCode` 或终态
`stopReason`，不能把未执行节点记为成功。

## 7. 模型 Space 排序与跨 Space

请求可以携带多个已授权 Space。模型只能对服务端给定列表排序，不能增加 Space、改变 tenant
或绕过授权；运行时直接按有序列表和当前位置执行，不创建“激活 Space”状态机。

使用 fixture 的 `CROSS_SPACE` 用例，并为起始 Space 打开 `NEXT_SPACE`：

```powershell
$body = @{
  query = 'OPS-FAILOVER-7 的跨地域故障切换检查项和回退条件是什么？'
  spaceIds = @($engineering, $operations)
  topK = 8
  testMode = $true
  retrievalTarget = '获得切换前检查、切换后探测和回退条件'
  evidenceRequirements = @(
    @{ id = 'precheck'; description = '跨地域切换前检查项' },
    @{ id = 'rollback'; description = '关键交易探测失败后的回退条件' }
  )
  configurationOverride = @{
    maximumRetrievalAttempts = 4
    chainNodeEnables = @{ NEXT_SPACE = $true }
    crossSpace = @{ enabled = $true; maximumSpaces = 2 }
  }
} | ConvertTo-Json -Depth 12
```

验收断言：`visitedSpaceIds` 保持模型决定的访问顺序；只有已授权输入列表中的 Space；发生
`NEXT_SPACE` 时观测包含 `SPACE_CHANGED`，后一个 Space 使用自己的配置修订和指纹；前一 Space
已获得的证据不会被清空。真实模型输出受模型状态影响，因此确定性顺序和跨 Space 证据保留以
`RetrievalMainChainAcceptanceTest.followsModelSpaceOrderAndRetainsEvidenceAcrossNextSpace`
作为强制门禁。

## 8. 逐层事件与中间失败

一次正常执行至少应从 `EXECUTION_STARTED` 开始，以 `EXECUTION_TERMINAL` 结束，并按实际路径包含：

```text
SPACE_ROUTING, CONFIGURATION_RESOLVED, QUERY_ANALYSIS, QUERY_PLANNING,
RETRIEVAL_PLAN, RETRIEVAL_BRANCH, FUSION, RERANK, COVERAGE_CHECK,
CHAIN_NODE_EVALUATED, CHAIN_NODE_COMPLETED, SPACE_CHANGED, EVIDENCE_BUILD
```

未执行的可选阶段可以跳过或明确记录 `SKIPPED/NOT_CONFIGURED`。每个物理召回分支都必须有自己的
终态事件；某一分支超时或失败时，其事件使用 `FAILED/TIMED_OUT/DEGRADED` 和稳定原因码，其他分支
仍可融合。未被层内降级逻辑处理的异常必须先发布 `STAGE_FAILURE`，其中只记录稳定组件码与输入计数，
再发布失败的 `EXECUTION_TERMINAL`；不得把异常消息或模型响应写入事件。整个请求最终仍必须发布
`EXECUTION_TERMINAL`；若发布/存储事件失败，检索业务 fail-open，
响应包含稳定观测 warning，已有事件投影会显示 `INCOMPLETE` 或缺失序列。

观测不得包含 Token、原始查询、Chunk 正文、候选正文或模型原始响应。公开 GET 只返回安全阶段事实、
有限维度指标和配置身份，不返回原始 payload。

## 9. Coverage

Coverage 开启时必须同时提供可执行的 `evidenceRequirements`，或由受控兜底产生要求。验收两条路径：

1. 充分：Coverage 分数达到 Space 阈值，响应 `terminalStatus=SUFFICIENT`、
   `stopReason=SUFFICIENCY_THRESHOLD_REACHED`，不再消耗后续节点。
2. 不充分：在固定 Chain 或尝试预算结束后响应 `INSUFFICIENT`，停止原因是预算或 Chain 终止原因；
   Judge 技术重试后仍失败则为 `CHECK_FAILED/COVERAGE_CHECK_FAILED`。

观测指标可以包含 `retrieval.coverage.score`、`retrieval.coverage.sufficient`、保留候选数和模型调用数；
Provider 未返回 Token 统计时不得生成值为零的 Token 指标。Coverage 分数是运行诊断，不是 Recall。

## 10. Reranker 全量回退

Reranker 成功时输出有限候选并记录组件/模型身份。受控地使 Reranker 超时、返回非法结构或不可用后，
验收以下不变量：

- `RERANK` 事件有明确降级原因，`retrieval.rerank.fallback=1`；
- 整个结果顺序和分数回到 RRF 结果，不能混用部分模型顺序；
- 检索可以继续构建 Evidence，并在响应 warnings 中记录稳定降级码；
- 不记录模型原始响应。

不要为了制造失败修改生产凭据；自动门禁
`RetrievalMainChainAcceptanceTest.fallsBackAsAWholeToRrfOrderWhenRerankerFails`
已经用受控失败实现验证该语义。

## 11. 测试广场指标隔离与观测查询

`testMode=true` 把执行用途固定为 `TEST_PLAZA`；普通调用为 `ONLINE`。两者可以使用同一事件存储，
但所有事实都带有限 `purpose` 维度，页面和外部观测适配器必须按用途过滤，不能把测试广场请求混入
线上口径。

查询完成后使用响应中的 `requestId` 下钻；Spring Event 同步消费通常可立即读取，若首次为 404，
允许短暂重试，但不能无限轮询：

```powershell
$observation = Invoke-RestMethod `
  -Uri "http://localhost:8080/api/v1/retrieval-observations/requests/$($result.requestId)" `
  -Headers $headers
```

验收断言：

- `requestId` 一致，`executionId` 唯一，`purpose=TEST_PLAZA`；
- `eventCount == events.Count`，sequence 严格递增；
- `completeness=COMPLETE` 时 `incompleteReasons` 和 `missingSequences` 均为空；
- 跨 Space 时 `visitedConfigurations` 按 `visitIndex` 排序并记录各自 revision/fingerprint；
- 指标只使用固定维度：Space、配置、策略、尝试、组件模型、索引版本、状态、停止原因、用途和时间片；
- Runtime 指标没有 `queryCase` 和 Gold 引用。只有真正绑定 Dataset/Case 的离线指标才可标记
  `OFFLINE_GOLD`。

其他租户的相同 `requestId` 返回 404；如果报告涉及任一当前主体不可读 Space，则整份报告拒绝访问，
不能只隐藏部分事件后返回。

控制台冒烟同时覆盖同一契约：

1. 在 Space 页面打开“检索配置”，确认当前修订、指纹、四通道、模型配置、固定 Chain 和跨 Space
   配置均可见；修改后保存，页面刷新为新修订。
2. 在 Retrieval 页面选择 Space，打开“测试广场模式”，使用“设置参数”产生单次覆盖并发起查询。
3. 结果区显示终态、停止原因、访问 Space 和配置指纹；观测面板显示用途、完整性、访问配置、
   事件时间线和指标维度。
4. 测试广场观测必须明确标识 `TEST_PLAZA`；关闭测试模式后的新请求显示 `ONLINE`，前一份报告
   不得被新请求的异步响应覆盖。

## 12. 数据库核验

以下命令只读，不修改验收数据：

```powershell
docker exec infinity-knowledge-postgres psql `
  -U infinity_knowledge -d infinity_knowledge -c @'
SELECT space_id, revision, fingerprint, created_at
FROM space_retrieval_configuration_version
WHERE tenant_id = 'demo'
ORDER BY space_id, revision;

SELECT request_id, execution_id, purpose, completeness, event_count,
       terminal_status, terminal_reason_code
FROM retrieval_execution_observation
WHERE tenant_id = 'demo'
ORDER BY updated_at DESC
LIMIT 20;

SELECT execution_id, sequence_number, visit_index, attempt_index,
       stage, status, reason_code, config_fingerprint
FROM retrieval_observation_event
WHERE tenant_id = 'demo'
ORDER BY stored_at DESC, sequence_number
LIMIT 100;

SELECT purpose, metric_key, aggregation, count(*) AS facts
FROM retrieval_metric_fact
WHERE tenant_id = 'demo'
GROUP BY purpose, metric_key, aggregation
ORDER BY purpose, metric_key, aggregation;
'@
```

检查点：配置修订追加而非更新；同一 execution 的 sequence 唯一；事件、执行投影和指标事实均有租户
边界；`ONLINE` 与 `TEST_PLAZA` 可按 purpose 分组；运行指标不能被当成 Gold 质量指标。

## 13. 聚焦自动化验收命令

按工程约束使用 `-T1` 和聚焦模块，不反复运行全仓 `clean verify`：

```powershell
.\mvnw.cmd -T1 -pl knowledge-retrieval -am `
  "-Dtest=RetrievalMainChainAcceptanceTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -T1 -pl knowledge-evaluation -am `
  "-Dtest=RetrievalEvaluationRunnerTest,RetrievalObservationIngestorTest,RetrievalObservationProcessorTest,RuntimeMetricFactProjectorTest,RetrievalObservationReportTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -T1 -pl store-postgres -am `
  "-Dtest=ObservationJsonCodecTest,PostgresRetrievalObservationReportReaderTest,PostgresRetrievalObservationStoreIT,RetrievalObservationRequestIndexMigrationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -T1 -pl control-plane -am `
  "-Dtest=KnowledgeQueryRequestTest,KnowledgeQueryResponseTest,SpaceRetrievalConfigurationControllerTest,SpaceRetrievalConfigurationServiceTest,RetrievalObservationControllerTest,RetrievalObservationReportServiceTest,RetrievalObservationSpringEventListenerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

Set-Location knowledge-console
npm.cmd run lint
npm.cmd run build
Set-Location ..
```

`PostgresRetrievalObservationStoreIT` 使用 Testcontainers，需要 Docker。真实外部验收还要执行第 3～12 节；
自动测试通过不代表真实模型排序质量、真实 Dense/Sparse 召回质量或生产 Dataset 的 Recall 已达标。

## 14. 通过标准

以下条件全部满足才算本轮检索升级可验收：

- Space 配置可读、可追加修订、可审计，乐观锁冲突明确；
- 单次覆盖不写回 Space，响应和观测能定位真实有效配置；
- Q0 始终参与，Chain 只能按固定顺序执行；
- 模型 Space 排序不扩大授权范围，`NEXT_SPACE` 保留既有证据；
- 每个实际执行层都有终态事实，中间失败不会导致整次观测无终态；
- Coverage 正常、预算耗尽和 Judge 失败三类终态可区分；
- Reranker 失败时整体回退到 RRF；
- `TEST_PLAZA` 与 `ONLINE` 指标按 purpose 隔离；
- 观测 GET 执行租户与全部 Space 授权，并且不暴露正文或模型原始响应；
- 只有带不可变 Gold 标签的 Dataset 才报告 Recall、MRR、nDCG。
