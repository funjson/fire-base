# ADR-005：Milvus 向量索引、多租户隔离与投影状态

## 状态

已接受并实现。

## 决策

1. 向量索引使用 Milvus standalone 作为第一版实现，业务代码只依赖 `VectorIndex` SPI。
2. 每个 Embedding 契约和 generation 使用独立 Collection：

   ```text
   {prefix}_{provider}_{model}_{dimensions}_{generation}
   ```

3. `tenant_id` 是 Partition Key，同时所有查询必须显式包含：

   - 当前租户；
   - 当前用户已授权知识空间；
   - 可选文档白名单。

4. Runtime 对所有向量结果再次执行租户和 ACL 校验。存储过滤是第一道边界，Runtime 校验是纵深防御。
5. 索引 generation 的指纹包括：

   - Embedding Provider；
   - Embedding 模型；
   - 向量维度；
   - generation 名；
   - Normalizer/Parser 版本；
   - Chunker 版本。

6. PostgreSQL 是 generation 和投影状态的事实源。文档写入成功后记录向量投影的
   `PENDING`、`SUCCEEDED` 或 `FAILED`。
7. Milvus 写入使用稳定 Chunk ID 幂等 Upsert，重试不会制造重复向量。

## 原因

共享 Collection 配合 Partition Key 能在第一阶段控制 Collection 数量，同时保留租户级数据定位；
查询表达式和 Runtime 双重授权校验避免依赖单一防线。独立 generation Collection 可以阻止不同模型、
维度或切块算法产生的向量进入同一可查询集合。

## 后果

- 正式修改 Embedding、Parser 或 Chunker 处理契约时，必须用新 `spaceId` 创建 Space 并重新
  摄取；旧 Space 的固化配置和索引保持不变。generation 继续隔离投影实现与部署契约，
  但不作为原 Space 内的配置提升或用户可编辑状态机。
- 多存储之间不使用分布式事务；外部投影失败必须保留可观察状态并支持重试。
- 超大租户或有物理隔离要求的租户，后续可通过同一 SPI 迁移到独立数据库或独立 Collection。
