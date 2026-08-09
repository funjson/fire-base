# ADR-001：采用 Domain、SPI、Runtime、Adapter 与 Control Plane 分层

## 状态

已接受。

## 决策

核心领域模型和端口不依赖 Spring、数据库客户端、Embedding 厂商或搜索引擎。Runtime 只依赖 Domain 与 SPI。所有外部系统通过独立 Adapter 实现，Control Plane 负责 HTTP、认证和依赖组装。

## 原因

该边界与 Infinity Agent 的工程风格一致，并允许离线确定性测试检索策略。厂商替换、索引迁移和 Agent 接入方式变化不会污染领域层。

## 后果

需要为基础设施对象与领域对象编写明确映射；禁止在领域类中使用 JPA、Neo4j 或 Elasticsearch 注解。

