# Phase 4 状态：Elasticsearch 关键词检索通道

> 历史说明：本文是 Phase 4 增量完成时的验证快照，不代表 2026-08-09
> 缺陷整改合并后的全量门禁结果。当前状态见 [Phase 1 状态](PHASE-1-STATUS.md)。

## 已完成

- 新增独立 `store-elasticsearch` 模块，基础设施类型不进入 Domain/SPI。
- 新修订与 `VECTOR`、`KEYWORD` 投影任务在同一个 PostgreSQL 事务中提交。
- 通用 Projection Worker 按已安装的执行器类型领取任务；关闭某个适配器时不会误领其历史任务。
- Elasticsearch 使用稳定 Chunk ID 幂等写入，成功后清理同文档旧修订。
- 使用 CJK analyzer 和多字段 BM25：`title^3`、`section_path^2`、`content`。
- `tenant_id`、`space_id`、可选 `document_id`、`source_type`、`language` 均在 Elasticsearch 查询内部过滤。
- Runtime 返回前继续执行第二次 tenant/ACL 校验。
- KEYWORD 投影具备 lease、指数退避、最大尝试次数、DEAD 状态、状态查询和人工重投。
- 修复异步投影源重载时丢失文档元数据与修订语言的问题。
- PostgreSQL 投影状态不再把尚未执行的 KEYWORD 错误标记为成功。

## 配置

```yaml
infinity:
  knowledge:
    keyword:
      elasticsearch:
        enabled: true
        endpoint: http://localhost:9200
        index-name: knowledge_chunks_v1
        api-key: ""
        username: ""
        password: ""
        connect-timeout: 5s
        request-timeout: 15s
```

本地开发环境关闭 Elasticsearch Security；生产环境支持 API Key 或 Basic
Authentication，凭据仅通过环境变量注入且不会写入 Trace。

## 本地启动

```powershell
docker compose --profile identity --profile search up -d
$env:KNOWLEDGE_ELASTICSEARCH_ENABLED = 'true'
java -jar control-plane\target\control-plane-0.1.0-SNAPSHOT.jar
```

同时启用向量通道：

```powershell
docker compose --profile identity --profile vector --profile search up -d
$env:KNOWLEDGE_VECTOR_ENABLED = 'true'
$env:KNOWLEDGE_ELASTICSEARCH_ENABLED = 'true'
```

Runtime 会并行执行 Elasticsearch KEYWORD 与 Milvus VECTOR，并用 RRF 融合。

## 已执行验证

- `mvn.cmd clean verify`：12 个 Reactor 模块全部通过。
- PostgreSQL 17 Testcontainers：5/5，通过双投影事务入队、按类型领取及独立状态更新。
- Elasticsearch 9.4.4 真实契约测试：1/1，通过跨租户同关键词与文档白名单隔离。
- API 端到端：

  ```text
  Markdown API
    -> KEYWORD PENDING
    -> Worker lease
    -> Elasticsearch bulk upsert
    -> KEYWORD SUCCEEDED (attempt=1)
    -> Agent query
    -> KEYWORD Evidence
    -> sufficient=true
  ```

- GLM + Milvus + Elasticsearch 混合端到端：VECTOR 与 KEYWORD 作业均一次成功，
  Evidence 合并通道为 `["VECTOR", "KEYWORD"]`，无降级 warning，
  `sufficient=true`。

## 未包含

- Elasticsearch alias 驱动的整代蓝绿切换。
- ICU 中文细粒度分词插件；当前使用 Elasticsearch 内置 CJK analyzer。
- Neo4j GraphRAG、LLM Wiki、PDF/DOCX/HTML 解析。
- 在线评测持久化、质量回归门禁与 Dashboard。
