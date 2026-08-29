package dev.infinityknowledge.domain.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 表示调用方要求最终证据覆盖的一项明确事实。
 *
 * <p>Requirement 由上游 Agent 或评测数据集提供，不包含权限和 Space 选择。其正文只在
 * Coverage Judge 调用内使用，不得进入日志、Trace 或观测事件；事件只记录数量和 ID。</p>
 *
 * @param id 单次请求内稳定标识
 * @param description 需要证据支持的事实描述
 */
public record EvidenceRequirement(String id, String description) {

    /** 校验稳定标识和模型输入长度。 */
    public EvidenceRequirement {
        id = DomainChecks.requiredText(id, "evidence requirement id", 64);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "evidence requirement id contains unsafe characters"
            );
        }
        description = DomainChecks.requiredText(
                description,
                "evidence requirement description",
                2_000
        );
    }
}
