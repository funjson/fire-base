# Phase 4 API 与运维说明

Phase 4 不新增业务路径，继续使用：

```http
POST /api/v1/documents/markdown
POST /api/v1/knowledge/query
GET  /api/v1/documents/{documentId}/projections
POST /api/v1/documents/{documentId}/projections/KEYWORD/retry
```

启用 Elasticsearch 后，新修订会出现 KEYWORD 作业：

```json
{
  "projectionType": "KEYWORD",
  "status": "SUCCEEDED",
  "attemptCount": 1,
  "lastErrorCode": null
}
```

检索成功后的 Evidence 通道包含：

```json
{
  "channels": ["KEYWORD"]
}
```

同时启用 Milvus 时，命中同一 Chunk 的通道可融合为：

```json
{
  "channels": ["KEYWORD", "VECTOR"]
}
```

真实适配器测试：

```powershell
docker compose --profile search up -d
$env:ELASTICSEARCH_IT_URI = 'http://localhost:9200'
mvn.cmd -pl store-elasticsearch -am verify
```
