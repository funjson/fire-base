# Phase 2 状态：GLM + Milvus 向量检索

## 已完成

- 智谱 `embedding-3` Provider 接入写入和查询链路，固定维度为 2048。
- `store-milvus` 适配器，实现 Collection 初始化、幂等 Upsert 和向量检索。
- Collection 名由 Provider、模型、维度和 generation 共同决定，避免不兼容向量混写。
- 使用 Milvus Partition Key 保存 `tenant_id`，并在所有查询表达式中强制加入租户过滤。
- 查询同时加入已授权知识空间以及可选文档白名单过滤。
- Runtime 对 Retriever 返回结果进行第二次租户、空间和文档授权校验。
- PostgreSQL 持久化 ACTIVE `index_generation` 和文档修订的 `document_index_projection` 状态。
- 向量投影记录 `PENDING -> SUCCEEDED` 或 `PENDING -> FAILED`，失败不会影响已提交的事实层文档。
- Markdown 写入 API 返回 `vectorStatus` 和 `warnings`，向量设施不可用时可显式降级。
- Docker Compose `vector` profile 提供 etcd、MinIO 和 Milvus standalone。

## 已执行验证

### 编译与单元测试

```powershell
mvn.cmd -pl control-plane -am test
```

通过 Domain、Runtime、Ingestion、Obsidian、GLM Provider、投影状态装饰器和 JWT 测试。

### 真实 Milvus 与 GLM 集成测试

```powershell
$env:MILVUS_IT_URI = 'http://localhost:19530'
$env:RUN_ZHIPU_TESTS = 'true'
mvn.cmd --% -pl store-milvus -am test `
  -Dtest=MilvusVectorIndexIT `
  -Dsurefire.failIfNoSpecifiedTests=false
```

验证内容：

- 两个租户写入相同向量，检索结果只包含授权租户。
- 调用真实 GLM API 生成 2048 维向量，写入 Milvus 后可完成语义召回。

### API 端到端验证

真实链路：

```text
Keycloak JWT
  -> Markdown ingestion
  -> PostgreSQL revision
  -> GLM embedding-3
  -> Milvus upsert
  -> semantic query
  -> ACL recheck
  -> EvidenceBundle
```

验收结果：

- 写入响应：`vectorStatus = SUCCEEDED`
- 语义改写问题：命中 `VECTOR` 通道
- Evidence：`sufficient = true`
- PostgreSQL：generation 为 `ACTIVE`，keyword/vector 均为 `SUCCEEDED`

## 启动方式

```powershell
docker compose --profile identity --profile vector up -d

$env:KNOWLEDGE_VECTOR_ENABLED = 'true'
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'

mvn.cmd -pl control-plane -am package
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar
```

`ZHIPU_API_KEY` 必须通过环境变量注入，系统不会输出该凭据或模型原始响应。

## 当前边界

- 投影当前在事实层事务提交后同步执行，并有持久化状态；尚未加入 Outbox、后台重试和死信队列。
- 尚未实现 Elasticsearch Adapter，当前关键词检索由 PostgreSQL 提供。
- 尚未实现 Neo4j Graph、LLM Wiki、PDF/DOCX/HTML 解析和离线评测运行器。
- generation 配置变化会被明确拒绝，蓝绿重建和原子切换 API 将在后续阶段实现。

下一阶段优先实现异步索引任务与重试、Elasticsearch 混合检索、离线 Retrieval Evaluation。
