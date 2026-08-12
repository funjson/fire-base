# knowledge-agent-client

该模块是 Agent 与 Knowledge Runtime 之间的薄 HTTP 边界。它只依赖 Java HTTP Client 和
Jackson，不依赖 Spring，也不依赖 Infinity-Agent 的内部 Runtime。

核心约束：

- 每次调用从 `AccessTokenProvider` 读取短期 Token，不缓存或记录凭证；
- 使用调用方提供的 UUID `X-Request-Id`，并校验响应 Header 与 Body 的关联标识；
- 只调用版本化的 `POST /api/v1/knowledge/query`；
- 传输失败、超时、鉴权失败和服务端错误使用稳定错误码分类；
- 不在客户端内部隐藏重试。Agent 可根据 `retryable`、自身 Deadline 和任务策略决定是否重试；
- 中断会传播为取消语义，并恢复线程中断标记。

示例：

```java
var client = new HttpKnowledgeSearchClient(
        URI.create("http://localhost:8080/"),
        HttpClient.newHttpClient(),
        JsonMapper.builder().build(),
        () -> serviceAccountToken,
        Duration.ofSeconds(35)
);

KnowledgeSearchResponse result = client.search(
        UUID.randomUUID(),
        new KnowledgeSearchRequest(
                "订单服务超时如何排查？",
                Set.of("engineering"),
                8,
                Map.of("language", "zh-CN")
        )
);
```

Infinity-Agent 中的 `AgentTool` 只需要把模型参数解码为 `KnowledgeSearchRequest`，调用该
客户端，并把返回 JSON 交给模型。鉴权、租户和用户身份仍由 JWT 决定，Agent 不能在请求体中
伪造租户或主体。

如果 Agent Runtime 需要直接消费 JSON 工具参数，可复用 `KnowledgeSearchTool`。它公开固定的
只读工具名称、JSON Schema，并将调用转为上述客户端请求；它故意不实现某个 Agent 框架的接口，
从而避免 Knowledge Runtime 反向依赖 Agent Runtime。
