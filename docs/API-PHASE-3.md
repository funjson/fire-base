# Phase 3 API：异步投影状态

## 写入

开启向量索引后：

```json
{
  "documentId": "uuid",
  "revisionId": "uuid",
  "changed": true,
  "vectorStatus": "QUEUED",
  "warnings": []
}
```

相同内容重复写入返回 `UNCHANGED`，不会重复创建任务。未启用向量能力返回 `SKIPPED`。

## 查询投影状态

```http
GET /api/v1/documents/{documentId}/projections
Authorization: Bearer <JWT>
```

示例：

```json
[
  {
    "id": "uuid",
    "projectionType": "VECTOR",
    "status": "SUCCEEDED",
    "attemptCount": 1,
    "lastErrorCode": null,
    "availableAt": "2026-07-26T10:00:00Z",
    "updatedAt": "2026-07-26T10:00:01Z"
  }
]
```

## 重投死信

```http
POST /api/v1/documents/{documentId}/projections/VECTOR/retry
Authorization: Bearer <JWT>
```

响应：

```json
{"requeued": true}
```

非 `DEAD` 状态或不存在时返回 `requeued: false`。所有查询和更新都强制绑定 JWT 中的 tenant。
