package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningRequest;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningResult;
import dev.infinityknowledge.spi.retrieval.QueryVariant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用 GLM 为程序已经选定的固定 Chain 节点生成一条检索表示。 */
public final class ZhipuFeedbackQueryPlanner implements FeedbackQueryPlanner {
    private static final String INSTRUCTION = """
            你是企业知识库检索链中的查询生成器，不是策略规划器，也不能回答问题。
            程序会给出固定 strategy，你只能按该 strategy 生成一条 query，并只输出
            {"query":"..."} JSON 对象。
            GAP_QUERY 只补 missingGaps；PRF 只从 evidenceMemory 中选择与原查询一致的区分词；
            STEP_BACK 生成更抽象但不改变主题的问题；HYDE 生成可能出现在目标语料中的假设文本。
            必须保留原查询中的错误码、接口名、产品型号、制度编号、版本、数值和否定语义。
            不得选择其他策略，不得输出租户、权限、Space、解释或最终答案。
            """;

    private final ZhipuJsonGenerationClient client;
    private final JsonMapper mapper;
    private final String model;

    /** 创建有界反馈查询生成适配器。 */
    public ZhipuFeedbackQueryPlanner(
            ZhipuJsonGenerationClient client,
            JsonMapper mapper,
            String model
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.model = DomainChecks.requiredText(model, "model", 128);
    }

    /** 调用模型并把输出绑定回调用方指定的策略。 */
    @Override
    public FeedbackQueryPlanningResult plan(FeedbackQueryPlanningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        JsonNode response = client.generate(INSTRUCTION, serialize(request));
        if (!response.isObject() || !response.path("query").isString()) {
            throw new GenerationProviderException(
                    "Zhipu feedback query response must contain a string query"
            );
        }
        String text;
        try {
            text = DomainChecks.requiredText(
                    response.path("query").asString(),
                    "feedback query",
                    16_000
            );
        } catch (IllegalArgumentException invalid) {
            throw new GenerationProviderException(
                    "Zhipu feedback query response contains an invalid query",
                    invalid
            );
        }
        String id = request.strategy().name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-') + "-1";
        return new FeedbackQueryPlanningResult(
                new QueryVariant(id, request.strategy(), text),
                "zhipu",
                model
        );
    }

    private String serialize(FeedbackQueryPlanningRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategy", request.strategy().name());
        payload.put("originalQuery", request.originalQuery());
        payload.put("retrievalTarget", request.retrievalTarget());
        payload.put("missingGaps", request.missingGaps());
        payload.put("evidenceMemory", request.evidenceMemory().stream().map(value -> Map.of(
                "candidateId", value.candidateId().toString(),
                "title", value.title(),
                "sectionPath", value.sectionPath(),
                "content", value.content()
        )).toList());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException failure) {
            throw new GenerationProviderException(
                    "Unable to serialize feedback query planning input",
                    failure
            );
        }
    }
}
