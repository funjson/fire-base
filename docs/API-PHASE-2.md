# Phase 2 API：向量状态与召回通道

启用向量能力后，Markdown 写入响应包含：

```json
{
  "documentId": "e0d974df-b9d3-3a57-9eee-14148ae124f1",
  "revisionId": "7461cd85-ea87-30c9-8be3-28e127f17234",
  "vectorStatus": "SUCCEEDED",
  "warnings": []
}
```

`vectorStatus` 取值：

- `SUCCEEDED`：GLM Embedding 和 Milvus 投影成功；
- `FAILED`：事实层文档已提交，但向量投影失败，`warnings` 提供脱敏错误；
- `SKIPPED`：未启用向量能力。

查询响应中每条 Evidence 的 `channels` 表明召回来源，例如 `VECTOR`、`KEYWORD`。
同一证据被多路命中时通过 RRF 融合。

## 启用向量通道

```powershell
$env:KNOWLEDGE_VECTOR_ENABLED = 'true'
$env:KNOWLEDGE_MILVUS_URI = 'http://localhost:19530'
$env:KNOWLEDGE_EMBEDDING_PROXY_HOST = '127.0.0.1'
$env:KNOWLEDGE_EMBEDDING_PROXY_PORT = '7890'
```

`ZHIPU_API_KEY` 只从环境变量读取。若不需要代理，省略两个代理变量。
