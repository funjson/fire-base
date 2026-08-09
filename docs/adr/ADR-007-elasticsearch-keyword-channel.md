# ADR-007：Elasticsearch 关键词投影与检索

## 状态

Accepted，2026-07-26。

## 决策

Elasticsearch 作为生产关键词检索通道；PostgreSQL FTS 保留为未启用 Elasticsearch
时的零依赖降级实现。两者不会在同一应用实例中重复注册。

Elasticsearch 同时实现 `KeywordIndex` 写端口和 `Retriever` 读端口。Control Plane
通过 `ProjectionExecutor` 把它接入通用事务投影 Worker。

## 一致性

1. PostgreSQL 提交不可变修订及 KEYWORD Outbox Job。
2. Worker 使用 lease 领取 KEYWORD Job。
3. Elasticsearch `_bulk` 使用稳定 `_id` 幂等写入新修订 Chunk。
4. 写入成功后删除同租户、同文档的旧修订。
5. 更新独立 `keyword_status` 并完成 Job。

该链路是至少一次语义。崩溃重试不会制造重复 Chunk。

## 安全边界

- `tenant_id` 是每次查询的强制 term filter。
- `space_id` 是强制 terms filter。
- 文档级 ACL 编译出白名单时，使用 `document_id` terms filter。
- Runtime 对候选结果再次检查 tenant、space 和 document。
- API 不接受请求体覆盖 tenant/user；身份仍来自受验证 JWT。

## 取舍

- 当前版本使用内置 CJK analyzer，部署简单且兼容中英文；专业中文分词作为后续可插拔 analyzer。
- 通过 JDK HTTP Client 调用稳定 REST API，避免 Elasticsearch 传输类型进入 SPI。
- 当前按文档修订清理旧记录；整代重建将使用 versioned index + alias 蓝绿发布。
