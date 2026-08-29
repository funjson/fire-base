package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.CoverageJudgmentRequest;
import dev.infinityknowledge.spi.retrieval.CoverageJudgmentResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 使用 GLM 对有限 Candidate 组合执行结构化 Evidence Coverage 判断。 */
public final class ZhipuCoverageJudge implements CoverageJudge {
    private static final String INSTRUCTION = """
            你是企业知识库的 Evidence Coverage Judge。你只判断给定候选内容是否覆盖证据要求，
            不回答问题、不补充候选之外的事实。先选择有序 retainedCandidateIds，再只依据保留集合
            计算 coverage 与 missingGaps。只输出 JSON：
            {"coverage":0到1的小数,"missingGaps":["..."],"retainedCandidateIds":["UUID"],
             "reasonCode":"稳定大写原因码"}。
            retainedCandidateIds 必须来自输入且不得超过 maximumRetainedCandidates。
            输入不会提供充分阈值、排序分数、剩余预算或后续策略，你不得推测这些信息。
            """;

    private final ZhipuJsonGenerationClient client;
    private final JsonMapper mapper;

    /** 创建有界 Coverage Judge 适配器。 */
    public ZhipuCoverageJudge(ZhipuJsonGenerationClient client, JsonMapper mapper) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /** 调用模型并严格解析 Coverage、缺口和候选 UUID。 */
    @Override
    public CoverageJudgmentResult judge(CoverageJudgmentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        JsonNode response = client.generate(INSTRUCTION, serialize(request));
        if (!response.isObject() || !response.path("coverage").isNumber()
                || !response.path("missingGaps").isArray()
                || !response.path("retainedCandidateIds").isArray()
                || !response.path("reasonCode").isString()) {
            throw new GenerationProviderException(
                    "Zhipu coverage response does not match the required JSON schema"
            );
        }
        List<String> gaps = new ArrayList<>();
        for (JsonNode gap : response.path("missingGaps")) {
            if (!gap.isString()) {
                throw new GenerationProviderException(
                        "Zhipu coverage missingGaps must contain strings"
                );
            }
            gaps.add(gap.asString());
        }
        List<UUID> retained = new ArrayList<>();
        for (JsonNode id : response.path("retainedCandidateIds")) {
            if (!id.isString()) {
                throw new GenerationProviderException(
                        "Zhipu coverage retainedCandidateIds must contain UUID strings"
                );
            }
            try {
                retained.add(UUID.fromString(id.asString()));
            } catch (IllegalArgumentException invalid) {
                throw new GenerationProviderException(
                        "Zhipu coverage response contains an invalid candidate UUID",
                        invalid
                );
            }
        }
        try {
            return new CoverageJudgmentResult(
                    response.path("coverage").asDouble(),
                    gaps,
                    retained,
                    DomainChecks.requiredText(
                            response.path("reasonCode").asString(),
                            "coverage reasonCode",
                            64
                    )
            );
        } catch (IllegalArgumentException invalid) {
            throw new GenerationProviderException(
                    "Zhipu coverage response contains invalid bounded values",
                    invalid
            );
        }
    }

    private String serialize(CoverageJudgmentRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalQuery", request.originalQuery());
        payload.put("retrievalTarget", request.retrievalTarget());
        payload.put("evidenceRequirements", request.evidenceRequirements());
        payload.put("maximumRetainedCandidates", request.maximumRetainedCandidates());
        payload.put("candidates", request.candidates().stream().map(value -> Map.of(
                "candidateId", value.candidateId().toString(),
                "title", value.title(),
                "sectionPath", value.sectionPath(),
                "content", value.content()
        )).toList());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException failure) {
            throw new GenerationProviderException(
                    "Unable to serialize coverage judgment input",
                    failure
            );
        }
    }
}
