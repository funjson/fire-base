# provider-zhipu

该模块通过智谱 Open API 实现 `EmbeddingProvider`。API Key 仅从注入配置读取，禁止写入日志、异常或持久化 Trace。

默认模型为 `embedding-3`、默认维度为 2048，调用方可通过 generation 配置覆盖。

