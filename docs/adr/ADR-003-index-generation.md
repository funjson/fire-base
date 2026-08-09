# ADR-003：索引采用不可变 Generation 与原子发布

## 状态

目标已接受，Phase 1 部分实现。

当前实现以 PostgreSQL `active_revision_id` 为事实源，Worker 发布前和检索返回后
都执行 Active Revision Guard；Embedding 契约使用不可变 collection generation。
跨 Keyword/Vector/Graph 的完整 generation 原子切换、Alias 蓝绿发布和 tombstone
清理尚未实现，不能把本 ADR 的目标态描述成当前能力。

## 决策

文档每次成功处理产生新的 `DocumentRevision` 和 `IndexGeneration`。关键词、向量和图投影全部完成后，通过 PostgreSQL 条件更新发布 generation。查询只读取活动 generation。

Embedding 模型 ID、模型版本、维度、Chunker 版本和规范化器版本都属于 generation 指纹。

## 原因

多存储没有可靠的分布式事务。原子切换活动 generation 可以避免查询读到半完成索引，也支持模型升级、回滚和蓝绿重建。

## 后果

旧 generation 需要异步回收；删除操作先发布 tombstone，再清理外部索引和对象存储。
