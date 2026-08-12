package dev.infinityknowledge.agent.client;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 将模型产生的 JSON 参数转换为知识查询的框架无关薄工具。
 *
 * <p>Infinity-Agent 等宿主只需要将自己的 ToolCall/ToolResult 契约包在该类外层，无需复制
 * HTTP、鉴权和错误分类逻辑。</p>
 */
public final class KnowledgeSearchTool {
    public static final String NAME = "search_enterprise_knowledge";
    public static final String ARGUMENT_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["query"],
             "properties":{"query":{"type":"string","minLength":1,"maxLength":16000},
             "spaceIds":{"type":"array","items":{"type":"string"},"uniqueItems":true},
             "topK":{"type":"integer","minimum":1,"maximum":100,"default":8},
             "filters":{"type":"object","additionalProperties":false,
             "properties":{"language":{"type":"string"},"sourceType":{"type":"string"}}}}}
            """;

    private final KnowledgeSearchClient client;
    private final JsonMapper jsonMapper;

    public KnowledgeSearchTool(KnowledgeSearchClient client, JsonMapper jsonMapper) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    /**
     * 执行一次只读知识检索并返回稳定 JSON Evidence。
     *
     * @param requestId 宿主 Agent 的端到端关联标识
     * @param argumentsJson 模型产生且尚未信任的参数 JSON
     * @return Knowledge API 响应 JSON
     */
    public String execute(UUID requestId, String argumentsJson) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(argumentsJson, "argumentsJson must not be null");
        try {
            ToolArguments arguments = jsonMapper.readValue(argumentsJson, ToolArguments.class);
            KnowledgeSearchResponse response = client.search(
                    requestId,
                    new KnowledgeSearchRequest(
                            arguments.query(),
                            arguments.spaceIds() == null ? Set.of() : arguments.spaceIds(),
                            arguments.topK() == null ? 8 : arguments.topK(),
                            arguments.filters() == null ? Map.of() : arguments.filters()
                    )
            );
            return jsonMapper.writeValueAsString(response);
        } catch (JacksonException invalidArguments) {
            throw new IllegalArgumentException("knowledge tool arguments are invalid", invalidArguments);
        }
    }

    private record ToolArguments(
            String query,
            Set<String> spaceIds,
            Integer topK,
            Map<String, String> filters
    ) {
    }
}
