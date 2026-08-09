# Phase 3 状态：可靠异步投影与离线评测

## 已完成

### 事务 Outbox

- 新文档修订和 `projection_job` 在同一个 PostgreSQL 事务中提交。
- 只有实际产生新修订时才创建任务；重复写入不会制造重复任务。
- `(tenant_id, revision_id, projection_type)` 唯一约束保证幂等。
- 任务只保存文档、修订和租户引用，不复制知识正文。

### Worker 可靠性

- `FOR UPDATE SKIP LOCKED` 支持多个实例并行领取。
- 每次领取具有 `lease_owner` 和 `lease_until`；进程退出后过期租约可被其他实例回收。
- 外部调用保持至少一次语义，Milvus 使用稳定 Chunk ID 幂等 Upsert。
- 指数退避、最大尝试次数和 `DEAD` 死信状态。
- 错误只保存稳定错误码，不保存知识正文、模型响应或凭据。
- 管理员可查询租户内文档投影状态，并显式重投死信任务。

### Evaluation Core

新增 `knowledge-evaluation` 模块，直接通过公开 `KnowledgeGateway` 运行数据集，支持：

- Hit Rate
- Recall@K
- MRR
- binary nDCG@K
- 每条 case 的指标明细

指标实现是纯 Java、无模型依赖，可用于 CI 回归门禁。

## API 行为

Markdown 写入启用向量能力时返回：

```json
{
  "vectorStatus": "QUEUED",
  "warnings": []
}
```

状态查询：

```http
GET /api/v1/documents/{documentId}/projections
```

死信重投：

```http
POST /api/v1/documents/{documentId}/projections/{projectionType}/retry
```

两个接口都从 JWT 获取 tenant，并要求 `knowledge-admin` 权限。

## 已执行验证

- 全模块编译与单元测试。
- PostgreSQL 17 Testcontainers：V3 Migration、事务入队、租约领取、精确修订加载和完成状态。
- 重试与死信状态机单元测试。
- 真实端到端链路：

  ```text
  API 返回 QUEUED
    -> Worker 领取 PENDING
    -> GLM embedding-3
    -> Milvus Upsert
    -> Job SUCCEEDED
    -> Agent VECTOR 语义召回
  ```

端到端结果：首次观察为 `PENDING`，一次尝试后变为 `SUCCEEDED`，语义 Evidence
命中 `VECTOR` 且 `sufficient = true`。

## 下一步

- Elasticsearch 9.x 外部关键词投影与 Retriever：已在
  [Phase 4](PHASE-4-STATUS.md) 完成。
- Evaluation Dataset/Run 的 PostgreSQL Repository 和管理 API。
- 评测基线对比、回归阈值和 CI Gate。
- Projection Job 指标、队列深度和死信告警。
