package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingRequest;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 使用 GLM 对服务端提供的已授权知识空间进行严格全排列。
 *
 * <p>适配器拒绝模型增加、删除或重复空间。模型仅能依据查询和空间摘要改变顺序，
 * 无法接触租户、主体或授权规则。</p>
 */
public final class ZhipuSpaceRouter implements SpaceRouter {
    private static final String INSTRUCTION = """
            你是企业知识库的空间路由器。你的任务只是对给定 allowedSpaces 排序，不是回答问题。
            只输出 JSON 对象，唯一字段为 orderedSpaceIds。
            orderedSpaceIds 必须包含输入中的每一个 spaceId，且每个只出现一次；不得新增、删除或修改标识。
            按空间名称、用途描述与 standaloneQuery 的相关性从高到低排序。
            不得输出解释、答案、权限结论或输入之外的空间。
            """;

    private final ZhipuJsonGenerationClient client;
    private final JsonMapper jsonMapper;
    private final String model;

    /**
     * 创建智谱空间排序适配器。
     *
     * @param client 有界 JSON 模型客户端
     * @param jsonMapper JSON 映射器
     * @param model 可观测模型标识
     */
    public ZhipuSpaceRouter(
            ZhipuJsonGenerationClient client,
            JsonMapper jsonMapper,
            String model
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.model = DomainChecks.requiredText(model, "model", 128);
    }

    /**
     * 调用模型并验证输出是 allowedSpaces 的完整排列。
     *
     * @param request 有界路由请求
     * @return 严格排序结果
     */
    @Override
    public SpaceRoutingResult rank(SpaceRoutingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        JsonNode response = client.generate(INSTRUCTION, serialize(request));
        JsonNode ordered = response.path("orderedSpaceIds");
        if (!ordered.isArray()) {
            throw new GenerationProviderException(
                    "Zhipu space routing response orderedSpaceIds must be an array"
            );
        }
        List<KnowledgeSpaceId> ids = java.util.stream.StreamSupport
                .stream(ordered.spliterator(), false)
                .map(ZhipuSpaceRouter::spaceId)
                .toList();
        Set<KnowledgeSpaceId> expected = request.allowedSpaces().stream()
                .map(SpaceRoutingCandidate::spaceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (ids.size() != expected.size()
                || new HashSet<>(ids).size() != ids.size()
                || !expected.equals(new HashSet<>(ids))) {
            throw new GenerationProviderException(
                    "Zhipu space routing response must be a complete allowed-space permutation"
            );
        }
        return new SpaceRoutingResult(ids, "zhipu", model);
    }

    /** 使用 JSON 序列化用户输入，避免文本拼接破坏结构边界。 */
    private String serialize(SpaceRoutingRequest request) {
        List<Map<String, String>> spaces = request.allowedSpaces().stream()
                .map(candidate -> Map.of(
                        "spaceId", candidate.spaceId().value(),
                        "name", candidate.name(),
                        "description", candidate.description()
                ))
                .toList();
        try {
            return jsonMapper.writeValueAsString(Map.of(
                    "standaloneQuery", request.standaloneQuery(),
                    "allowedSpaces", spaces
            ));
        } catch (JacksonException serializationFailure) {
            throw new GenerationProviderException(
                    "Unable to serialize space routing input",
                    serializationFailure
            );
        }
    }

    /** 拒绝非字符串或非法空间标识。 */
    private static KnowledgeSpaceId spaceId(JsonNode node) {
        if (!node.isString()) {
            throw new GenerationProviderException(
                    "Zhipu space routing response contains a non-string space id"
            );
        }
        try {
            return new KnowledgeSpaceId(node.asString());
        } catch (IllegalArgumentException invalid) {
            throw new GenerationProviderException(
                    "Zhipu space routing response contains an invalid space id",
                    invalid
            );
        }
    }
}
