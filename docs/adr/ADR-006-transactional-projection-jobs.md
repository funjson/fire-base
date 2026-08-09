# ADR-006：外部索引采用事务任务、租约和至少一次投递

## 状态

已接受并实现。

## 决策

文档事实层与外部索引之间不使用分布式事务。每次新修订发布时，在同一个 PostgreSQL
事务内写入 `projection_job`。后台 Worker 使用有界租约领取任务，外部投影按至少一次语义执行。

任务状态：

```text
PENDING -> RUNNING -> SUCCEEDED
                   -> RETRY -> RUNNING
                   -> DEAD
```

Worker 规则：

- 使用 `FOR UPDATE SKIP LOCKED` 防止多个节点重复领取同一个有效租约；
- RUNNING 租约过期后允许重新领取；
- 每次领取增加 `attempt_count`；
- 失败按照指数退避设置下一次 `available_at`；
- 达到最大尝试次数后进入 `DEAD`；
- 人工重投清空错误码和尝试次数，回到 `RETRY`。

## 原因

PostgreSQL、Milvus、Elasticsearch 和 Neo4j 不存在一个适合该系统的共享事务协调器。事务任务保证
“事实修订已发布但投影任务丢失”不会发生；租约和幂等 Upsert 允许系统在进程崩溃、网络超时和
不确定提交结果下安全恢复。

## 后果

- 写入 API 表示“已接受并排队”，不再等待 Embedding 或向量数据库。
- Agent 可能在短暂窗口内只能使用已经完成的检索通道。
- 外部索引 Adapter 必须使用稳定主键实现幂等。
- 运维必须监控队列深度、任务年龄、重试率和死信数量。
